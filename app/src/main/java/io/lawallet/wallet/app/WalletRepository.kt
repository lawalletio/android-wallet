package io.lawallet.wallet.app

import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.nwc.NwcTransaction
import io.lawallet.wallet.core.nwc.NwcUri
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.WalletAddress
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** UI transaction (transactions.ts `NwcTransactionItem`): sats + milliseconds. */
@Serializable
data class Tx(
    val type: String,
    val amountSats: Long,
    val feesPaidSats: Long,
    val description: String?,
    val paymentHash: String,
    val preimage: String?,
    val settledAt: Long?,
    val createdAt: Long,
    val state: String,
    val invoice: String? = null,
) {
    val incoming get() = type == "incoming"

    companion object {
        fun from(t: NwcTransaction) = Tx(
            type = t.type,
            amountSats = t.amount / 1000,
            feesPaidSats = (t.feesPaid ?: 0) / 1000,
            description = t.description?.takeIf { it.isNotBlank() },
            paymentHash = t.paymentHash,
            preimage = t.preimage?.takeIf { it.isNotBlank() },
            settledAt = t.settledAt?.let { it * 1000 },
            createdAt = t.createdAt * 1000,
            state = t.state ?: if (t.settledAt != null) "settled" else "pending",
            invoice = t.invoice,
        )
    }
}

data class PaymentNotice(val id: String, val type: String, val tx: Tx)

/**
 * One key per payment, whichever source reported it (23196, 23197, or a catch-up row): the hash
 * alone, never the type label. A hashless tx falls back to its own fields, never a constant.
 */
fun paymentKey(tx: Tx): String = tx.paymentHash.ifEmpty { "nohash:${tx.invoice ?: "${tx.type}:${tx.createdAt}:${tx.amountSats}"}" }

/**
 * Activity rows, newest first: [new] wins over [old] for the same payment. A payment between two addresses on the
 * same wallet shows up in it twice (sent and received, one hash), so the direction is part of a row's identity.
 */
fun mergeActivity(new: List<Tx>, old: List<Tx>, max: Int = 200): List<Tx> =
    (new + old).distinctBy { it.type + ":" + paymentKey(it) }.sortedByDescending { it.createdAt }.take(max)

/**
 * The single NWC connection for the app (nwc-provider.tsx + use-nwc-balance.ts): balance polling,
 * notification subscription with backoff, gap catch-up via list_transactions, activity cache.
 */
class WalletRepository(
    private val session: Session,
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private companion object { const val SWITCH_DEBOUNCE_MS = 400L }
    private val json = Json { ignoreUnknownKeys = true }
    private val _client = MutableStateFlow<NwcClient?>(null)
    val client: StateFlow<NwcClient?> = _client
    private var nwcKey: String? = null
    private var currentNwc: String? = "<unset>"

    private val _balance = MutableStateFlow<Long?>(null)
    val balance: StateFlow<Long?> = _balance
    private val _balanceLoading = MutableStateFlow(true)
    val balanceLoading: StateFlow<Boolean> = _balanceLoading
    private val _error = MutableStateFlow<String?>(null)
    val relayError: StateFlow<String?> = _error
    private var silentStrikes = 0
    private val _silent = MutableStateFlow(false)
    /**
     * The wallet itself stopped answering (two balance reads in a row got no NWC reply, or an error from it), as
     * opposed to its relay being down. For a disposable LNCurl wallet that usually means the provider removed it.
     */
    val walletSilent: StateFlow<Boolean> = _silent

    private val _recent = MutableStateFlow<List<Tx>>(emptyList())
    val recent: StateFlow<List<Tx>> = _recent

    private val _notices = MutableSharedFlow<PaymentNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<PaymentNotice> = _notices

    /**
     * Payments that just arrived (live or caught up), by [paymentKey]: their row plays an entrance once,
     * then [consumeFresh] drops them. Marked before the list updates, so the new row already sees it.
     */
    private val fresh = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun consumeFresh(key: String): Boolean = fresh.remove(key)

    /** True while the app is visible: drives balance polling (notifications stream regardless). */
    val active = MutableStateFlow(false)
    private var jobs: List<Job> = emptyList()

    val hasWallet: Boolean get() = _client.value != null

    private val addressesSer = ListSerializer(WalletAddress.serializer())
    private val _addresses = MutableStateFlow(prefs.cached("addresses", addressesSer).orEmpty())
    /** The account's Lightning addresses (cached under "addresses", shared with the addresses screen). */
    val addresses: StateFlow<List<WalletAddress>> = _addresses
    fun setAddresses(list: List<WalletAddress>) { _addresses.value = list; prefs.setCached("addresses", addressesSer, list) }
    suspend fun refreshAddresses() { runCatching { session.api.value.addresses() }.onSuccess { setAddresses(it) } }

    private val _selected = MutableStateFlow(prefs.selectedAddress)
    /** The username picked on Home's address pill: a local view choice (the primary address stays as it is). Null = primary. */
    val selectedAddress: StateFlow<String?> = _selected
    fun selectAddress(username: String?) { _selected.value = username; prefs.selectedAddress = username }

    /** Addresses Home can switch between, primary first (an alias shows where it forwards; one without a wallet says so). */
    fun switchable(list: List<WalletAddress> = _addresses.value) = list.sortedByDescending { it.isPrimary }

    /** The remote wallet [username] routes to, when it's a non-primary address with one of its own (else the primary's is used). */
    fun walletOf(username: String?, list: List<WalletAddress>): String? =
        list.find { it.username == username && !it.isPrimary && it.mode == "CUSTOM_NWC" }?.remoteWalletId

    /** The address in use: the selected one when it still exists, else the primary. */
    fun shownAddress(me: CurrentUser?): String? {
        val primary = me?.lightningAddress ?: return null
        val sel = _selected.value?.takeIf { u -> switchable().any { it.username == u && !it.isPrimary } } ?: return primary
        return sel + "@" + primary.substringAfter('@')
    }

    init {
        scope.launch {
            val me = session.me.distinctUntilChanged { a, b -> a?.nwc == b?.nwc && a?.userId == b?.userId }
            combine(me, _selected, _addresses) { m, sel, list -> m to walletOf(sel, list) }.distinctUntilChanged().collectLatest { (m, walletId) -> connect(resolveFor(m, walletId)) }
        }
        scope.launch {
            session.me.filterNotNull().distinctUntilChanged { a, b -> a.userId == b.userId && a.lightningAddress == b.lightningAddress }.collect { refreshAddresses() }
        }
        // Logout or an instance switch (the account goes away): its addresses and pick go too, not just from disk.
        scope.launch {
            var had = false
            session.me.collect { m -> if (had && m == null) { _addresses.value = emptyList(); _selected.value = null }; had = m != null }
        }
        scope.launch { active.filter { it }.collect { refreshBalance() } }
    }

    /**
     * `/api/users/me` only exposes a connection through the primary Lightning address. A new
     * account has none yet, so its courtesy LNCurl wallet would be unusable: fall back to the
     * account's default ACTIVE NWC remote wallet (owner-only connection-string endpoint).
     */
    private suspend fun resolveNwc(me: io.lawallet.wallet.core.api.CurrentUser?): String? {
        me ?: return null
        me.nwc?.let { return it }
        return runCatching {
            val api = session.api.value
            val wallet = api.remoteWallets().filter { it.status == "ACTIVE" && it.type == "NWC" }
                .sortedByDescending { it.isDefault }.firstOrNull() ?: return null
            api.remoteWalletConnectionString(wallet.id)
        }.fold(
            { nwc -> nwc.also { withContext(Dispatchers.IO) { prefs.setSecretCached("nwc", it) } } },
            // Offline: keep using the last connection we resolved.
            { withContext(Dispatchers.IO) { prefs.secretCached("nwc") } },
        )
    }

    /**
     * The one rule for which wallet the app uses (Home, sends, receives): the selected address's own wallet
     * ([walletId], from [walletOf]) when it routes to one, else the primary's. Cached, so flicking between addresses
     * is instant; [fresh] re-reads it from the server first. Never falls back to the primary for a [walletId]: Home
     * would show, and a send would spend, another wallet's sats under the selected address.
     */
    private suspend fun resolveFor(me: CurrentUser?, walletId: String?, fresh: Boolean = false): String? {
        if (me == null || walletId == null) return resolveNwc(me)
        val key = "nwc:$walletId"
        val cached = withContext(Dispatchers.IO) { prefs.secretCached(key) }
        if (cached != null && !fresh) return cached
        return runCatching { session.api.value.remoteWalletConnectionString(walletId) }.getOrNull()
            ?.also { withContext(Dispatchers.IO) { prefs.setSecretCached(key, it) } }
            ?: cached
    }

    /** [resolveFor] the address selected right now. */
    private suspend fun resolveSelected(me: CurrentUser?, fresh: Boolean = false) = resolveFor(me, walletOf(_selected.value, _addresses.value), fresh)

    /** Idempotent: callers that just refreshed `me` use it to get the client without waiting for the collector. */
    private fun connect(nwc: String?) {
        if (nwc == currentNwc) return
        currentNwc = nwc
        // The relay socket is shared (RelayPool) and stays up: cancelling the jobs only CLOSEs this wallet's subs.
        jobs.forEach { it.cancel() }
        // A switch (not the first connect) waits a beat, so flicking through wallets sends no REQs for the ones skipped.
        val settle = if (_client.value != null) SWITCH_DEBOUNCE_MS else 0L
        val uri = nwc?.let { runCatching { NwcUri.parse(it) }.getOrNull() }
        if (uri == null) {
            _client.value = null
            nwcKey = null
            _balance.value = null
            _balanceLoading.value = session.me.value == null
            _recent.value = emptyList()
            return
        }
        val key = sha256(nwc.toByteArray()).toHex().take(16)
        nwcKey = key
        if (prefs.lastSeen(key) == 0L) prefs.setLastSeen(key, System.currentTimeMillis() / 1000)
        _balance.value = prefs.cachedBalance(key)
        _recent.value = prefs.activityCache(key)?.let { runCatching { json.decodeFromString(ListSerializer(Tx.serializer()), it) }.getOrNull() } ?: emptyList()
        val c = NwcClient(uri, http)
        silentStrikes = 0; _silent.value = false
        _client.value = c
        jobs = listOf(
            scope.launch { delay(settle); pollLoop(c) },
            scope.launch { delay(settle); notificationLoop(c, key) },
            scope.launch {
                // Every reconnect after the current state: catch up on payments missed while offline.
                c.relay.connected.drop(1).filter { it }.collect { catchUp(c, key) }
            },
            scope.launch { c.relay.lastError.collect { if (it != null && _balance.value == null) _error.value = it } },
            scope.launch { delay(settle); refreshActivity() },
        )
    }

    private suspend fun pollLoop(c: NwcClient) {
        while (scope.isActive) {
            if (active.value) refreshBalance(c)
            delay(30_000)
        }
    }

    /** The balance read in flight, shared: callers asking at once (Home opening, foreground, a payment) wait for one. */
    private var balanceRead: Pair<NwcClient, kotlinx.coroutines.Deferred<Unit>>? = null

    suspend fun refreshBalance(c: NwcClient? = _client.value) {
        c ?: return
        val read = balanceRead?.takeIf { it.first === c && it.second.isActive }?.second
            ?: scope.async { readBalance(c) }.also { balanceRead = c to it }
        read.await()
    }

    private suspend fun readBalance(c: NwcClient) {
        try {
            val sats = c.getBalanceMsat() / 1000
            _balance.value = sats
            _error.value = null
            silentStrikes = 0; _silent.value = false
            nwcKey?.let { prefs.setCachedBalance(it, sats) }
        } catch (e: Exception) {
            _error.value = e.message ?: "Wallet relay unavailable"
            // ponytail: two strikes, so one slow answer doesn't suggest destroying a wallet; tune if it flags live ones.
            if (e is NwcException && c === _client.value && ++silentStrikes >= 2) _silent.value = true
        } finally {
            _balanceLoading.value = false
        }
    }

    suspend fun refreshActivity(): List<Tx> {
        val c = _client.value ?: return emptyList()
        return runCatching { c.listTransactions(limit = 25).map(Tx::from) }
            .onSuccess { merge(it) }
            .getOrDefault(_recent.value)
    }

    suspend fun page(until: Long?, limit: Int = 25): List<Tx> {
        val c = _client.value ?: return emptyList()
        return c.listTransactions(limit = limit, until = until).map(Tx::from).also { merge(it) }
    }

    fun findTx(paymentHash: String) = _recent.value.firstOrNull { it.paymentHash == paymentHash }

    private fun merge(txs: List<Tx>) {
        val merged = mergeActivity(txs, _recent.value)
        // Every Home visit refetches the same page: skip re-encoding up to 200 txs on the main thread.
        if (merged == _recent.value) return
        _recent.value = merged
        nwcKey?.let { prefs.setActivityCache(it, json.encodeToString(ListSerializer(Tx.serializer()), merged)) }
    }

    private suspend fun notificationLoop(c: NwcClient, key: String) {
        var backoff = 1000L
        while (scope.isActive) {
            try {
                c.notifications().collect { n ->
                    backoff = 1000L
                    onPayment(key, n.type, Tx.from(n.tx))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun catchUp(c: NwcClient, key: String) {
        val since = prefs.lastSeen(key).takeIf { it > 0 } ?: return
        runCatching { c.listTransactions(from = since, limit = 50) }.getOrNull()
            ?.map(Tx::from)?.filter { it.state == "settled" }
            ?.sortedBy { it.settledAt ?: it.createdAt }
            // A reconnect can bring several at once: each is noticed, but the list is merged (and saved) and the
            // balance read once for the batch, not once per payment.
            ?.filter { notice(key, if (it.incoming) "payment_received" else "payment_sent", it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { merge(it); refreshBalance() }
    }

    private suspend fun onPayment(key: String, type: String, tx: Tx) {
        if (!notice(key, type, tx)) return
        merge(listOf(tx))
        refreshBalance()
    }

    /** Marks [tx] seen and announces it; false when it already was (no notice). */
    private suspend fun notice(key: String, type: String, tx: Tx): Boolean {
        val id = paymentKey(tx)
        val seen = prefs.seen(key)
        // Pre-#19 keys were "$type:$hash": honour them so an upgrade doesn't re-notify the last payment.
        if (id in seen || "$type:${tx.paymentHash}" in seen) return false
        prefs.setSeen(key, seen + id)
        prefs.setLastSeen(key, maxOf(prefs.lastSeen(key), (tx.settledAt ?: tx.createdAt) / 1000))
        fresh += id
        _notices.emit(PaymentNotice(id, type, tx))
        return true
    }

    /** Debug builds only (the web's `?devPayment=`): fake a payment to exercise notices and the balance roll. */
    suspend fun simulatePayment(incoming: Boolean, sats: Long) {
        val now = System.currentTimeMillis()
        val tx = Tx(if (incoming) "incoming" else "outgoing", sats, 0, "Dev payment", "dev-$now", null, now, now, "settled")
        _balance.value = ((_balance.value ?: 0) + if (incoming) sats else -sats).coerceAtLeast(0)
        // In memory only (never the activity cache): the next refresh drops it.
        fresh += paymentKey(tx)
        _recent.value = listOf(tx) + _recent.value
        _notices.emit(PaymentNotice("dev:$now", if (incoming) "payment_received" else "payment_sent", tx))
    }

    /**
     * Re-reads the connection before a send/receive (the server may have swapped a dead courtesy wallet, or
     * rotated the selected address's) and returns the client for the wallet Home shows — wallet-nwc.ts
     * `getFreshWalletNwc`, per address.
     */
    suspend fun freshClient(): NwcClient? {
        runCatching { session.refreshMe() }.onSuccess { connect(resolveSelected(it, fresh = true)) }
        return _client.value
    }

    /** Receive with no wallet + lncurl_auto_create → mint a courtesy wallet (receive/amount-step.tsx). */
    suspend fun ensureWallet(): NwcClient? {
        freshClient()?.let { return it }
        // The selected address has a wallet of its own that just didn't resolve: not a reason to mint the account a new default.
        if (walletOf(_selected.value, _addresses.value) != null) return null
        val settings = session.settings.value ?: session.refreshSettings()
        if (settings?.lncurlAutoCreate == true || settings?.lncurlEnabled == true) {
            session.api.value.createLncurlWallet(isDefault = true)
            connect(resolveSelected(session.refreshMe()))
        }
        return _client.value
    }
}

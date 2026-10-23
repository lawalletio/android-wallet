package io.lawallet.wallet.app

import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import io.lawallet.wallet.core.nwc.NwcClient
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
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
 * The single NWC connection for the app (nwc-provider.tsx + use-nwc-balance.ts): balance polling,
 * notification subscription with backoff, gap catch-up via list_transactions, activity cache.
 */
class WalletRepository(
    private val session: Session,
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
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

    private val _recent = MutableStateFlow<List<Tx>>(emptyList())
    val recent: StateFlow<List<Tx>> = _recent

    private val _notices = MutableSharedFlow<PaymentNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<PaymentNotice> = _notices

    /** True while the app is visible: drives balance polling (notifications stream regardless). */
    val active = MutableStateFlow(false)
    private var jobs: List<Job> = emptyList()

    val hasWallet: Boolean get() = _client.value != null

    init {
        scope.launch {
            session.me.distinctUntilChanged { a, b -> a?.nwc == b?.nwc && a?.userId == b?.userId }.collect { me -> connect(resolveNwc(me)) }
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

    /** Idempotent: callers that just refreshed `me` use it to get the client without waiting for the collector. */
    private fun connect(nwc: String?) {
        if (nwc == currentNwc) return
        currentNwc = nwc
        jobs.forEach { it.cancel() }
        _client.value?.close()
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
        val c = NwcClient(uri, http, scope)
        _client.value = c
        jobs = listOf(
            scope.launch { pollLoop(c) },
            scope.launch { notificationLoop(c, key) },
            scope.launch {
                // Every reconnect after the first one: catch up on payments missed while offline.
                c.relay.connected.drop(1).filter { it }.collect { catchUp(c, key) }
            },
            scope.launch { c.relay.lastError.collect { if (it != null && _balance.value == null) _error.value = it } },
        )
        scope.launch { refreshActivity() }
    }

    private suspend fun pollLoop(c: NwcClient) {
        while (scope.isActive) {
            if (active.value) refreshBalance(c)
            delay(30_000)
        }
    }

    suspend fun refreshBalance(c: NwcClient? = _client.value) {
        c ?: return
        try {
            val sats = c.getBalanceMsat() / 1000
            _balance.value = sats
            _error.value = null
            nwcKey?.let { prefs.setCachedBalance(it, sats) }
        } catch (e: Exception) {
            _error.value = e.message ?: "Wallet relay unavailable"
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
        val merged = (txs + _recent.value).distinctBy { it.paymentHash.ifEmpty { it.createdAt.toString() } }.sortedByDescending { it.createdAt }.take(200)
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
            ?.forEach { onPayment(key, if (it.incoming) "payment_received" else "payment_sent", it) }
    }

    private suspend fun onPayment(key: String, type: String, tx: Tx) {
        val id = "$type:${tx.paymentHash}"
        val seen = prefs.seen(key)
        if (id in seen) return
        prefs.setSeen(key, seen + id)
        prefs.setLastSeen(key, maxOf(prefs.lastSeen(key), (tx.settledAt ?: tx.createdAt) / 1000))
        merge(listOf(tx))
        _notices.emit(PaymentNotice(id, type, tx))
        refreshBalance()
    }

    /** Debug builds only (the web's `?devPayment=`): fake a payment to exercise notices and the balance roll. */
    suspend fun simulatePayment(incoming: Boolean, sats: Long) {
        val now = System.currentTimeMillis()
        val tx = Tx(if (incoming) "incoming" else "outgoing", sats, 0, "Dev payment", "dev-$now", null, now, now, "settled")
        _balance.value = ((_balance.value ?: 0) + if (incoming) sats else -sats).coerceAtLeast(0)
        _notices.emit(PaymentNotice("dev:$now", if (incoming) "payment_received" else "payment_sent", tx))
    }

    /**
     * Re-reads /api/users/me before a send/receive (the server may have swapped a dead
     * courtesy wallet) and returns the current client — wallet-nwc.ts `getFreshWalletNwc`.
     */
    suspend fun freshClient(): NwcClient? {
        runCatching { session.refreshMe() }.onSuccess { connect(resolveNwc(it)) }
        return _client.value
    }

    /** Receive with no wallet + lncurl_auto_create → mint a courtesy wallet (receive/amount-step.tsx). */
    suspend fun ensureWallet(): NwcClient? {
        freshClient()?.let { return it }
        val settings = session.settings.value ?: session.refreshSettings()
        if (settings?.lncurlAutoCreate == true || settings?.lncurlEnabled == true) {
            session.api.value.createLncurlWallet(isDefault = true)
            connect(resolveNwc(session.refreshMe()))
        }
        return _client.value
    }
}

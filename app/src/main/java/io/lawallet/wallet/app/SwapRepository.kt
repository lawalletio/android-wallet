package io.lawallet.wallet.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.lawallet.wallet.LaWalletApp
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.api.Swap
import io.lawallet.wallet.core.api.SwapQuote
import io.lawallet.wallet.core.ln.BankDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit

/** A bank account paid before, with the name the user confirmed for it. */
@Serializable data class BankRecipient(val account: BankAccount, val holderName: String, val usedAt: Long)

/**
 * ARS cash-out orders (lawallet-nwc swaps): the account's orders, saved bank recipients, CBU/alias lookups, and the
 * status polling that turns a PENDING order into a notice. Polls every 5 s while an order is on screen, every 30 s
 * while the app is in front with one pending, and through [SwapWorker] (15 min) when the app is gone.
 */
class SwapRepository(
    private val context: Context,
    private val session: Session,
    private val prefs: io.lawallet.wallet.core.store.Prefs,
    private val wallet: WalletRepository,
    scope: CoroutineScope,
) {
    private val api get() = session.api.value
    val lookupEnabled: Boolean get() = session.settings.value?.bankLookupEnabled == true

    private val _swaps = MutableStateFlow(prefs.cached(SWAPS, SWAP_LIST).orEmpty())
    /** Newest first. */
    val swaps: StateFlow<List<Swap>> = _swaps

    private val _recipients = MutableStateFlow(prefs.cached(RECIPIENTS, RECIPIENT_LIST).orEmpty())
    val recipients: StateFlow<List<BankRecipient>> = _recipients

    private val _notices = MutableSharedFlow<Swap>(extraBufferCapacity = 8)
    /** An order that left PENDING after its sats were sent: delivered or failed. */
    val notices: SharedFlow<Swap> = _notices

    /** The order on screen, polled fast. */
    val watching = MutableStateFlow<String?>(null)

    /** Preimages whose `paid` report failed; retried on the next poll (the provider finds the payment anyway). */
    private val unreported = mutableMapOf<String, String>()
    private val lookups = mutableMapOf<String, BankAccount?>()
    private var lookupsBlockedUntil = 0L

    init {
        // History from the server once the instance says cash-out is on (and on every login).
        scope.launch {
            combine(session.settings, session.auth) { st, a -> st?.swapsEnabled == true && a == AuthState.LoggedIn }.distinctUntilChanged()
                .collect { if (it) refresh() }
        }
        scope.launch {
            combine(watching, wallet.active, _swaps.map { l -> l.any { it.status == "PENDING" } }.distinctUntilChanged()) { w, active, pending -> Triple(w, active, pending) }
                .collectLatest { (w, active, pending) ->
                    if (pending) scheduleWorker() else cancelWorker()
                    if (w == null && !(active && pending)) return@collectLatest
                    while (true) {
                        runCatching { refreshPending() }
                        delay(if (w != null) 5_000 else 30_000)
                    }
                }
        }
    }

    fun byId(id: String) = _swaps.value.firstOrNull { it.id == id }
    fun byHash(hash: String) = hash.takeIf { it.isNotEmpty() }?.let { h -> _swaps.value.firstOrNull { it.paymentHash == h } }

    /** The account's orders from the server (any device), newest 50. */
    suspend fun refresh() {
        if (session.settings.value?.swapsEnabled != true) return
        val remote = runCatching { api.swaps() }.getOrNull() ?: return
        val ids = remote.map { it.id }.toSet()
        save(remote + _swaps.value.filter { it.id !in ids })
    }

    /** Re-reads every PENDING order; true while any is still pending. */
    suspend fun refreshPending(): Boolean {
        val pending = _swaps.value.filter { it.status == "PENDING" }
        for (s in pending) {
            unreported[s.id]?.let { pre -> runCatching { api.swapPaid(s.id, pre) }.onSuccess { unreported.remove(s.id); put(it) } }
            runCatching { api.swap(s.id) }.onSuccess { put(it) }.onFailure { if (it is ApiException && it.status == 404) remove(s.id) }
        }
        return _swaps.value.any { it.status == "PENDING" }
    }

    suspend fun refresh(id: String): Swap = api.swap(id).also { put(it) }

    suspend fun quote(amountArs: Double): List<SwapQuote> = api.swapQuote(amountArs)

    /**
     * Owner and bank of a CBU/CVU or alias; null when unknown or the lookup is off. Answers (misses too) are kept for
     * the session: the upstream allows 30 lookups a minute for the whole server.
     */
    suspend fun lookup(query: String): BankAccount? {
        val key = query.trim().lowercase()
        if (!lookupEnabled || BankDestination.kind(key) == null) return null
        if (key in lookups) return lookups[key]
        _recipients.value.firstOrNull { it.account.owners.isNotEmpty() && (it.account.cvu == key || it.account.alias?.lowercase() == key) }?.let { return it.account }
        if (System.currentTimeMillis() < lookupsBlockedUntil) throw ApiException(429, "rate limited")
        return try {
            api.bankAccount(key).also { lookups[key] = it }
        } catch (e: ApiException) {
            when (e.status) {
                404, 400 -> { lookups[key] = null; null }
                429 -> { lookupsBlockedUntil = System.currentTimeMillis() + 60_000; throw e }
                else -> throw e
            }
        }
    }

    /** Saved recipients whose CBU, alias or name contains [input]; all of them when it's blank. */
    fun matching(input: String): List<BankRecipient> {
        val q = input.trim().lowercase()
        return if (q.isEmpty()) _recipients.value
        else _recipients.value.filter { r -> r.account.cvu.contains(q) || r.account.alias?.lowercase()?.contains(q) == true || r.holderName.lowercase().contains(q) }
    }

    fun recipientFor(destination: String) = _recipients.value.firstOrNull { it.account.cvu == destination || it.account.alias.equals(destination, true) }

    /** Locks a rate with [provider]: the order's invoice to pay. The recipient is saved for next time. */
    suspend fun create(provider: String, amountArs: Double, account: BankAccount, holderName: String?): Swap {
        val swap = api.createSwap(provider, amountArs, account.cvu.takeIf { it.length == 22 } ?: account.alias ?: account.cvu, holderName)
        remember(BankRecipient(account, holderName ?: swap.holderName.orEmpty(), System.currentTimeMillis()))
        put(swap, notify = false)
        return swap
    }

    /** Pays the order's invoice from the wallet in use and tells the server (best effort; polling catches up). */
    suspend fun pay(swap: Swap): Swap {
        val client = wallet.freshClient() ?: throw IllegalStateException("No wallet connected")
        val res = client.payInvoice(swap.bolt11, null)
        val pre = res.preimage
        val paid = runCatching { api.swapPaid(swap.id, pre) }.getOrElse {
            if (pre.isNotBlank()) unreported[swap.id] = pre
            swap.copy(paidAt = java.time.Instant.now().toString())
        }
        put(paid, notify = false)
        wallet.refreshBalance(); wallet.refreshActivity()
        return paid
    }

    /** Drops an unpaid order (409 once paid: then it stays). */
    suspend fun cancel(id: String) {
        runCatching { api.cancelSwap(id) }.onSuccess { put(it, notify = false) }
    }

    suspend fun receipt(id: String, share: Boolean) = api.swapReceipt(id, share)

    /** Takes a saved account off Send's suggestions. */
    fun forget(r: BankRecipient) = saveRecipients(_recipients.value.filter { it != r })

    private fun saveRecipients(next: List<BankRecipient>) {
        _recipients.value = next
        prefs.setCached(RECIPIENTS, RECIPIENT_LIST, next)
    }

    private fun remember(r: BankRecipient) {
        fun key(x: BankRecipient) = x.account.cvu.ifEmpty { x.account.alias.orEmpty() }
        saveRecipients((listOf(r) + _recipients.value.filter { key(it) != key(r) }).take(12))
    }

    private fun put(s: Swap, notify: Boolean = true) {
        val old = byId(s.id)
        if (notify && old?.status == "PENDING" && s.status in DONE && (s.paidAt != null || old.paidAt != null)) _notices.tryEmit(s)
        save(listOf(s) + _swaps.value.filter { it.id != s.id })
    }

    private fun remove(id: String) = save(_swaps.value.filter { it.id != id })

    private fun save(list: List<Swap>) {
        val sorted = list.sortedByDescending { it.createdAt ?: "" }.take(100)
        _swaps.value = sorted
        prefs.setCached(SWAPS, SWAP_LIST, sorted)
    }

    private fun scheduleWorker() = runCatching {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SwapWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SwapWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    private fun cancelWorker() = runCatching { WorkManager.getInstance(context).cancelUniqueWork(SwapWorker.NAME) }

    private companion object {
        const val SWAPS = "swaps"
        const val RECIPIENTS = "bank-recipients"
        val SWAP_LIST = ListSerializer(Swap.serializer())
        val RECIPIENT_LIST = ListSerializer(BankRecipient.serializer())
        val DONE = setOf("COMPLETED", "FAILED")
    }
}

/** Checks pending cash-out orders while the app is closed; a delivered one posts its notification. */
class SwapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LaWalletApp).container
        if (c.prefs.loginMethod == null) return Result.success()
        // An external signer (Amber, bunker) can't approve a new session from here: wait for the app instead.
        val jwt = c.prefs.jwt
        if (c.prefs.loginMethod in setOf("amber", "bunker") && (jwt == null || LaWalletApi.expiresSoon(jwt))) return Result.success()
        c.swaps.refreshPending()
        delay(1_000) // the notice collector runs on the main thread
        return Result.success()
    }

    companion object { const val NAME = "swap-status" }
}

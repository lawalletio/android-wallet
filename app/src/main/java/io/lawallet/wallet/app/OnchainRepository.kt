package io.lawallet.wallet.app

import android.content.Context
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.DescriptorPair
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.Electrum
import io.lawallet.wallet.core.btc.Payjoin
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.core.diag.Trail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.BumpFeeTxBuilder
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignersContainer
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Txid
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong

/** An onchain tx as the UI shows it. [delta] is the wallet's net change in sats (a send's includes its fee). */
data class OnchainTx(
    val txid: String,
    val delta: Long,
    val fee: Long?,
    /** sat/vB. */
    val feeRate: Double?,
    /** Null while unconfirmed. */
    val height: Int?,
    /** Milliseconds: confirmation time, or when first seen. */
    val time: Long,
    /** Sent: where it went. Received: the address of ours it paid. */
    val counterpart: String?,
    /** Signals BIP-125 replaceability: an unconfirmed send of ours can be sped up. */
    val rbf: Boolean,
    /** A send: what its recipients got. Counted from the outputs, since [delta] nets out a self-transfer to zero. */
    val paid: Long? = null,
) {
    val incoming get() = delta >= 0
    val pending get() = height == null
    /** What moved, fee aside. */
    val amount get() = if (incoming) delta else paid ?: (abs(delta) - (fee ?: 0))
    fun confirmations(tip: Int?) = if (height == null || tip == null) 0 else (tip - height + 1).coerceAtLeast(1)

    companion object {
        /**
         * A send's (paid, fee), from our side only. A payjoin also spends the receiver's coins ([allOut] + [fee] is
         * more than [ourIn]) and pays them back to them, so its outputs to others aren't all ours: what we paid is what
         * we [asked] to (kept at send), else all that left us less the fee. An unknown fee means inputs we can't see.
         */
        fun sendShare(delta: Long, toOthers: Long, allOut: Long, ourIn: Long, fee: Long?, asked: Long?): Pair<Long?, Long?> = when {
            asked != null -> asked to (-delta - asked)
            fee == null -> null to null
            allOut + fee > ourIn -> null to fee
            else -> toOthers.takeIf { it > 0 } to fee
        }
    }

    /** As the app's [Tx], so Activity rows and grouping take it unchanged (the txid stands in for the payment hash). */
    fun toTx() = Tx(
        type = if (incoming) "incoming" else "outgoing", amountSats = amount, feesPaidSats = if (incoming) 0 else fee ?: 0,
        description = null, paymentHash = txid, preimage = null, settledAt = if (pending) null else time, createdAt = time,
        state = if (pending) "pending" else "settled",
    )
}

data class OnchainBalance(val total: Long, val confirmed: Long, val pending: Long)

data class OnchainState(
    val balance: OnchainBalance? = null,
    val txs: List<OnchainTx> = emptyList(),
    /** The address to show for receiving: unused, rotating once paid. */
    val receive: String? = null,
    val syncing: Boolean = false,
    val error: String? = null,
)

/** Fee rates (sat/vB) for the next block, about an hour, and about a day. */
data class Fees(val fast: Double, val medium: Double, val slow: Double)

/** A built, unsigned transaction. [psbt] is base64; [replaces] is the txid a fee bump replaces. */
data class OnchainDraft(val walletId: String, val psbt: String, val address: String, val amount: Long, val fee: Long, val feeRate: Double, val replaces: String? = null)

sealed interface SendOutcome {
    /** [payjoin]: null when not tried; true when the receiver joined, paying [extraFee] more from our change; false when it fell back. */
    data class Sent(val txid: String, val payjoin: Boolean? = null, val extraFee: Long = 0) : SendOutcome
    /** Signed with what this phone holds (maybe nothing): other signers have to add theirs. */
    data class NeedsSignatures(val psbt: String) : SendOutcome
}

/** How far along a PSBT is: [signed] of [required] signatures on every input. */
data class PsbtStatus(val signed: Int, val required: Int, val fee: Long, val sends: List<Pair<String, Long>>) {
    val complete get() = signed >= required
}

class OnchainException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NO_FUNDS, DUST, FEE_TOO_LOW, NO_KEY, WRONG_WALLET, GAP, NOT_READY }
}

/**
 * The onchain wallets' engine: one BDK wallet per [OnchainWallet] (SQLite in files/onchain, public descriptors only),
 * kept current through one [Electrum] subscription socket per chain. A changed scripthash status syncs that wallet
 * (a full scan when it was a look-ahead address); nothing polls. Seed phrases are decrypted only to sign.
 */
class OnchainRepository(context: Context, private val store: OnchainStore, private val scope: CoroutineScope, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "onchain").apply { mkdirs() }
    private val cache = context.getSharedPreferences("onchain-cache", Context.MODE_PRIVATE)

    private val _states = MutableStateFlow<Map<String, OnchainState>>(emptyMap())
    val states: StateFlow<Map<String, OnchainState>> = _states
    private val _received = MutableSharedFlow<Pair<OnchainWallet, OnchainTx>>(extraBufferCapacity = 16)
    /** A payment just arrived (new incoming tx in a wallet that had synced before). */
    val received: SharedFlow<Pair<OnchainWallet, OnchainTx>> = _received
    private val _confirmed = MutableSharedFlow<Pair<OnchainWallet, OnchainTx>>(extraBufferCapacity = 16)
    /** A tx this wallet had as pending was included in a block (its first confirmation), sent or received. */
    val confirmed: SharedFlow<Pair<OnchainWallet, OnchainTx>> = _confirmed

    private inner class Handle(@Volatile var w: OnchainWallet) {
        val lock = Mutex()
        var bdk: Wallet? = null
        var persister: Persister? = null
        /** scripthash → is a look-ahead (unrevealed) script. */
        @Volatile var hashes: Map<String, Boolean> = emptyMap()
        val incoming = ConcurrentHashMap<String, String?>()
        @Volatile var full = false
        var job: Job? = null
        var shown: UInt? = null
        var synced = false
    }

    private val handles = ConcurrentHashMap<String, Handle>()
    private val electrums = ConcurrentHashMap<Chain, Electrum>()
    private val clients = ConcurrentHashMap<Chain, ElectrumClient>()
    /** Last synced status per scripthash, per chain: unchanged ones need no sync on reconnect. */
    private val seen = ConcurrentHashMap<String, String>()
    private val reconcile = Mutex()
    @Volatile private var running = false

    init {
        cache.getString("seen", null)?.split(';')?.forEach { e -> e.split('=').takeIf { it.size == 2 }?.let { seen[it[0]] = it[1] } }
        // Last known balances, so Home has a figure before the first sync.
        _states.value = store.wallets.value.associate { w ->
            w.id to OnchainState(balance = cache.getString("bal:${w.id}", null)?.split(',')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size == 3 }?.let { OnchainBalance(it[0], it[1], it[2]) })
        }
        scope.launch(Dispatchers.IO) { store.wallets.collect { reconcile(it) } }
        Chain.entries.forEach { chain -> scope.launch { store.electrum(chain).drop(1).collect { restart(chain) } } }
    }

    fun state(id: String) = _states.value[id]
    fun tip(chain: Chain): StateFlow<Int?> = electrum(chain).tip
    fun connected(chain: Chain): StateFlow<Boolean> = electrum(chain).connected

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    /** App in front, or the background listener on: keep the subscriptions open. */
    fun start() {
        running = true
        store.wallets.value.map { it.chain }.distinct().forEach { electrum(it).start() }
    }

    fun stop() {
        running = false
        electrums.values.forEach { it.stop() }
    }

    private fun electrum(chain: Chain): Electrum = electrums.getOrPut(chain) {
        Electrum(store.electrum(chain).value, scope, allowTcp = BuildConfig.DEBUG).also { e ->
            scope.launch { e.status.collect { (hash, status) -> onStatus(hash, status) } }
            e.watch(handles.values.filter { it.w.chain == chain }.flatMap { it.hashes.keys })
            if (running) e.start()
        }
    }

    /** The server changed: a new connection; every wallet on the chain re-syncs against it. */
    private fun restart(chain: Chain) {
        electrums.remove(chain)?.stop()
        clients.remove(chain)?.close()
        handles.values.filter { it.w.chain == chain }.forEach { h -> h.hashes.keys.forEach(seen::remove) }
        electrum(chain)
    }

    private suspend fun reconcile(list: List<OnchainWallet>) = reconcile.withLock {
        val ids = list.map { it.id }.toSet()
        handles.keys.filter { it !in ids }.forEach { id -> handles.remove(id)?.let { drop(it) } }
        for (w in list) {
            val h = handles[w.id]
            if (h != null) { h.w = w; continue }
            runCatching { load(w) }.onFailure { e ->
                Trail.add("onchain", "load failed: ${e.javaClass.simpleName}", error = true)
                setState(w.id) { it.copy(error = e.javaClass.simpleName) }
            }
        }
        if (running) list.map { it.chain }.distinct().forEach { electrum(it).start() }
    }

    private suspend fun drop(h: Handle) = h.lock.withLock {
        h.job?.cancel()
        h.hashes.keys.forEach(seen::remove)
        h.bdk?.close(); h.persister?.close()
        dir.listFiles { f -> f.name.startsWith(h.w.id) }?.forEach { it.delete() }
        cache.edit().remove("bal:${h.w.id}").apply()
        _states.update { it - h.w.id }
    }

    /** Public descriptors for a record made before they were stored (or null: a single address). */
    private fun derive(w: OnchainWallet): DescriptorPair? = when (w.watch) {
        null -> store.privateKey(w.id)?.let { Descriptors.fromXprv(it, w.script ?: return null, w.chain) }
            ?: store.secret(w.id)?.let { (words, pass) -> Descriptors.fromPhrase(words, pass, w.script ?: return null, w.chain) }
        WatchKind.XPUB -> Descriptors.fromXpub(w.source ?: return null, w.script ?: return null, w.chain)
        WatchKind.DESCRIPTOR, WatchKind.MULTISIG -> Descriptors.fromDescriptor(w.source ?: return null, w.chain)
        WatchKind.ADDRESS -> null
    }

    private suspend fun load(w0: OnchainWallet) {
        var w = w0
        val h = Handle(w)
        if (w.descriptors == null && w.watch != WatchKind.ADDRESS) {
            val d = derive(w) ?: error("no descriptors")
            store.update(w.id) { it.copy(descriptors = d) }
            w = w.copy(descriptors = d)
            h.w = w
        }
        val d = w.descriptors
        if (d == null) {
            val script = Address(w.source!!, w.chain.bdk).scriptPubkey().toBytes()
            h.hashes = mapOf(Descriptors.scripthash(script) to false)
            h.full = true
        } else {
            val p = Persister.newSqlite(File(dir, "${w.id}.sqlite").path)
            val ext = Descriptor(d.external, w.chain.kind)
            val int = Descriptor(d.internal, w.chain.kind)
            h.persister = p
            h.bdk = runCatching { Wallet.load(ext, int, p) }.getOrElse { Wallet(ext, int, w.chain.bdk, p).also { it.persist(p) } }
            h.synced = h.bdk!!.latestCheckpoint().height > 0u
            h.full = !h.synced
            h.hashes = scripts(h.bdk!!)
            if (h.synced) publish(h)
        }
        handles[w.id] = h
        electrum(w.chain).watch(h.hashes.keys)
        // Never synced: scan now. Otherwise the subscribe answers say whether anything changed.
        if (h.full) schedule(h)
    }

    // ---- sync ---------------------------------------------------------------------------------------------------

    /** Every revealed script plus [GAP] unrevealed ones on each chain, by scripthash. */
    private fun scripts(bdk: Wallet): Map<String, Boolean> = buildMap {
        for (k in listOf(KeychainKind.EXTERNAL, KeychainKind.INTERNAL)) {
            val last = bdk.derivationIndex(k)?.toInt() ?: -1
            for (i in 0..last + GAP) put(Descriptors.scripthash(bdk.peekAddress(k, i.toUInt()).address.scriptPubkey().toBytes()), i > last)
        }
    }

    private fun onStatus(hash: String, status: String?) {
        val h = handles.values.firstOrNull { hash in it.hashes } ?: return
        if (seen[hash] == status || (status == null && !seen.containsKey(hash))) return
        h.incoming[hash] = status
        if (h.hashes[hash] == true) h.full = true
        schedule(h)
    }

    /** Debounced: a burst of status changes (a tx touching many addresses) is one sync. */
    private fun schedule(h: Handle) {
        if (h.job?.isActive == true) return
        h.job = scope.launch(Dispatchers.IO) { delay(400); sync(h) }
    }

    /** Re-reads a wallet now (pull to refresh). */
    suspend fun refresh(id: String) { handles[id]?.let { sync(it) } }

    private fun client(chain: Chain): ElectrumClient = clients.getOrPut(chain) {
        val url = store.electrum(chain).value
        if (Electrum.parse(url).first == "tcp" && !BuildConfig.DEBUG) throw OnchainException(OnchainException.Reason.NOT_READY)
        ElectrumClient(url)
    }

    private suspend fun sync(h: Handle) = withContext(Dispatchers.IO) {
        h.lock.withLock {
            val w = h.w
            val committing = HashMap(h.incoming)
            setState(w.id) { it.copy(syncing = true) }
            try {
                val before = _states.value[w.id]?.txs?.map { it.txid }?.toSet().orEmpty()
                val waiting = _states.value[w.id]?.txs?.filter { it.pending }?.map { it.txid }?.toSet().orEmpty()
                val bdk = h.bdk
                if (bdk == null) syncAddress(h) else {
                    val now = (System.currentTimeMillis() / 1000).toULong()
                    val c = client(w.chain)
                    val update = if (h.full) c.fullScan(bdk.startFullScanAt(now).build(), GAP.toULong(), BATCH, true)
                    else c.sync(bdk.startSyncWithRevealedSpksAt(now).build(), BATCH, true)
                    bdk.applyUpdate(update)
                    update.close()
                    bdk.persist(h.persister!!)
                    h.full = false
                    publish(h)
                    val fresh = scripts(bdk)
                    if (fresh.keys != h.hashes.keys) { h.hashes = fresh; electrum(w.chain).watch(fresh.keys) }
                }
                committing.forEach { (k, v) -> if (v == null) seen.remove(k) else seen[k] = v; h.incoming.remove(k, v) }
                saveSeen()
                val txs = _states.value[w.id]?.txs.orEmpty()
                if (h.synced) txs.filter { it.incoming && it.txid !in before }.forEach { _received.tryEmit(w to it) }
                txs.filter { !it.pending && it.txid in waiting }.forEach { _confirmed.tryEmit(w to it) }
                h.synced = true
                setState(w.id) { it.copy(syncing = false, error = null) }
            } catch (e: Exception) {
                clients.remove(w.chain)?.close()
                Trail.add("onchain", "sync failed: ${e.javaClass.simpleName}")
                setState(w.id) { it.copy(syncing = false, error = e.javaClass.simpleName) }
            }
        }
    }

    /** A single watched address has no descriptor: its balance and history come from Electrum directly. */
    private suspend fun syncAddress(h: Handle) {
        val w = h.w
        val e = electrum(w.chain)
        val hash = h.hashes.keys.first()
        val script = Address(w.source!!, w.chain.bdk).scriptPubkey().toBytes()
        val bal = e.call("blockchain.scripthash.get_balance", JsonArray(listOf(JsonPrimitive(hash)))).jsonObject
        val confirmed = bal["confirmed"]!!.jsonPrimitive.long
        val unconfirmed = bal["unconfirmed"]!!.jsonPrimitive.long
        val history = e.call("blockchain.scripthash.get_history", JsonArray(listOf(JsonPrimitive(hash)))).jsonArray.takeLast(50)
        val raw = history.associate { item ->
            val id = item.jsonObject["tx_hash"]!!.jsonPrimitive.content
            id to Transaction(hex(e.call("blockchain.transaction.get", JsonArray(listOf(JsonPrimitive(id)))).jsonPrimitive.content))
        }
        // Our outputs, so a later tx spending one counts as sent.
        val ours = raw.flatMap { (id, tx) -> tx.output().mapIndexedNotNull { i, o -> if (o.scriptPubkey.toBytes().contentEquals(script)) "$id:$i" to o.value.toSat().toLong() else null } }.toMap()
        val now = System.currentTimeMillis()
        val txs = history.map { item ->
            val id = item.jsonObject["tx_hash"]!!.jsonPrimitive.content
            val height = item.jsonObject["height"]!!.jsonPrimitive.int.takeIf { it > 0 }
            val tx = raw.getValue(id)
            val received = tx.output().filter { it.scriptPubkey.toBytes().contentEquals(script) }.sumOf { it.value.toSat().toLong() }
            val sent = tx.input().sumOf { ours["${it.previousOutput.txid}:${it.previousOutput.vout}"] ?: 0L }
            val others = tx.output().filter { !it.scriptPubkey.toBytes().contentEquals(script) }
            OnchainTx(id, received - sent, null, null, height, now, if (sent > 0) others.firstOrNull()?.let { addressOf(it.scriptPubkey.toBytes(), w.chain) } else w.source, tx.isExplicitlyRbf(),
                others.sumOf { it.value.toSat().toLong() }.takeIf { sent > 0 && it > 0 })
        }.sortedWith(compareBy<OnchainTx> { !it.pending }.thenByDescending { it.height ?: Int.MAX_VALUE })
        setBalance(w.id, OnchainBalance(confirmed + unconfirmed, confirmed, unconfirmed))
        setState(w.id) { it.copy(txs = txs, receive = w.source) }
    }

    private fun addressOf(script: ByteArray, chain: Chain) = runCatching { Address.fromScript(org.bitcoindevkit.Script(script), chain.bdk).toString() }.getOrNull()

    private fun publish(h: Handle) {
        val bdk = h.bdk ?: return
        val w = h.w
        val b = bdk.balance()
        val now = System.currentTimeMillis() / 1000
        val txs = bdk.transactions().mapNotNull { ct ->
            val txid = ct.transaction.computeTxid()
            val d = bdk.txDetails(txid) ?: return@mapNotNull null
            val conf = ct.chainPosition as? ChainPosition.Confirmed
            val sent = d.sent.toSat() > 0u
            val outs = ct.transaction.output()
            // A send's recipients: outputs to others, or on a transfer to ourselves, those to our receive keychain (not change).
            val paidTo = if (!sent) emptyList() else outs.filter { !bdk.isMine(it.scriptPubkey) }
                .ifEmpty { outs.filter { bdk.derivationOfSpk(it.scriptPubkey)?.keychain == KeychainKind.EXTERNAL } }
            val to = (if (sent) paidTo.firstOrNull() ?: outs.firstOrNull() else outs.firstOrNull { bdk.isMine(it.scriptPubkey) })
            val fee = d.fee?.toSat()?.toLong()
            val (paid, ourFee) = if (!sent) null to fee
                else OnchainTx.sendShare(d.balanceDelta, paidTo.sumOf { it.value.toSat().toLong() }, outs.sumOf { it.value.toSat().toLong() },
                    d.sent.toSat().toLong(), fee, cache.getString("pj:$txid", null)?.toLongOrNull())
            OnchainTx(
                txid.toString(), d.balanceDelta, ourFee, d.feeRate?.let { it.toSatPerKwu().toDouble() * 4 / 1000 },
                conf?.confirmationBlockTime?.blockId?.height?.toInt(),
                1000 * (conf?.confirmationBlockTime?.confirmationTime?.toLong() ?: (ct.chainPosition as? ChainPosition.Unconfirmed)?.timestamp?.toLong() ?: now),
                to?.let { addressOf(it.scriptPubkey.toBytes(), w.chain) }, ct.transaction.isExplicitlyRbf(),
                paid,
            )
        }.sortedWith(compareBy<OnchainTx> { !it.pending }.thenByDescending { it.time })
        val unused = bdk.listUnusedAddresses(KeychainKind.EXTERNAL)
        val receive = h.shown?.let { i -> unused.firstOrNull { it.index == i } }?.address?.toString()
            ?: bdk.nextUnusedAddress(KeychainKind.EXTERNAL).address.toString().also { bdk.persist(h.persister!!) }
        setBalance(w.id, OnchainBalance(b.total.toSat().toLong(), b.confirmed.toSat().toLong(), (b.trustedPending.toSat() + b.untrustedPending.toSat()).toLong()))
        setState(w.id) { it.copy(txs = txs, receive = receive) }
    }

    private fun setBalance(id: String, b: OnchainBalance) {
        cache.edit().putString("bal:$id", "${b.total},${b.confirmed},${b.pending}").apply()
        setState(id) { it.copy(balance = b) }
    }

    private fun setState(id: String, f: (OnchainState) -> OnchainState) = _states.update { it + (id to f(it[id] ?: OnchainState())) }

    private fun saveSeen() = cache.edit().putString("seen", seen.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    // ---- receive ------------------------------------------------------------------------------------------------

    /** A new address past the current one. Null for a single watched address; throws GAP past 20 unused in a row. */
    suspend fun newAddress(id: String): String? = withContext(Dispatchers.IO) {
        val h = handles[id] ?: return@withContext null
        h.lock.withLock {
            val bdk = h.bdk ?: return@withLock null
            if (bdk.listUnusedAddresses(KeychainKind.EXTERNAL).size >= GAP) throw OnchainException(OnchainException.Reason.GAP)
            val info = bdk.revealNextAddress(KeychainKind.EXTERNAL)
            bdk.persist(h.persister!!)
            h.shown = info.index
            h.hashes = scripts(bdk)
            electrum(h.w.chain).watch(h.hashes.keys)
            info.address.toString().also { a -> setState(id) { it.copy(receive = a) } }
        }
    }

    // ---- send ---------------------------------------------------------------------------------------------------

    suspend fun fees(chain: Chain): Fees = withContext(Dispatchers.IO) {
        val e = electrum(chain).also { if (!running) it.start() }
        suspend fun est(blocks: Int) = runCatching { Electrum.satPerVb(e.call("blockchain.estimatefee", JsonArray(listOf(JsonPrimitive(blocks)))).jsonPrimitive.double) }.getOrNull()
        val fast = (est(1) ?: est(2) ?: MIN_RATE).coerceAtLeast(MIN_RATE)
        val medium = (est(6) ?: fast).coerceIn(MIN_RATE, fast)
        val slow = (est(144) ?: medium).coerceIn(MIN_RATE, medium)
        Fees(fast, medium, slow)
    }

    private fun handle(id: String) = handles[id] ?: throw OnchainException(OnchainException.Reason.NOT_READY)
    private fun reason(e: org.bitcoindevkit.CreateTxException) = when (e) {
        is org.bitcoindevkit.CreateTxException.OutputBelowDustLimit -> OnchainException.Reason.DUST
        is org.bitcoindevkit.CreateTxException.FeeTooLow, is org.bitcoindevkit.CreateTxException.FeeRateTooLow -> OnchainException.Reason.FEE_TOO_LOW
        else -> OnchainException.Reason.NO_FUNDS
    }
    private fun rate(satVb: Double) = FeeRate.fromSatPerKwu((satVb * 250).roundToLong().coerceAtLeast(25).toULong())

    /** Builds (doesn't sign) a payment of [sats] to [address]; null [sats] sends everything (no change). */
    suspend fun draft(id: String, address: String, sats: Long?, satVb: Double): OnchainDraft = withContext(Dispatchers.IO) {
        val h = handle(id)
        h.lock.withLock {
            val bdk = h.bdk ?: throw OnchainException(OnchainException.Reason.NO_KEY)
            val script = Address(address, h.w.chain.bdk).scriptPubkey()
            val b = TxBuilder().feeRate(rate(satVb))
            val psbt = try {
                (if (sats == null) b.drainWallet().drainTo(script) else b.addRecipient(script, Amount.fromSat(sats.toULong()))).finish(bdk)
            } catch (e: org.bitcoindevkit.CreateTxException) {
                throw OnchainException(reason(e))
            }
            // Building reveals a change address: keep it, so the next draft doesn't reuse it.
            bdk.persist(h.persister!!)
            val tx = psbt.extractTxUncheckedFeeRate()
            val amount = tx.output().filter { it.scriptPubkey.toBytes().contentEquals(script.toBytes()) }.sumOf { it.value.toSat().toLong() }
            OnchainDraft(id, psbt.serialize(), address, amount, psbt.fee().toLong(), satVb)
        }
    }

    /** Speeds up an unconfirmed send of ours: the same payment at [satVb], replacing [txid] (BIP-125). */
    suspend fun bump(id: String, txid: String, satVb: Double): OnchainDraft = withContext(Dispatchers.IO) {
        val h = handle(id)
        h.lock.withLock {
            val bdk = h.bdk ?: throw OnchainException(OnchainException.Reason.NO_KEY)
            val psbt = try { BumpFeeTxBuilder(Txid.fromString(txid), rate(satVb)).finish(bdk) } catch (e: org.bitcoindevkit.CreateTxException) {
                throw OnchainException(reason(e))
            }
            bdk.persist(h.persister!!)
            val out = psbt.extractTxUncheckedFeeRate().output().firstOrNull { !bdk.isMine(it.scriptPubkey) }
            OnchainDraft(id, psbt.serialize(), out?.let { addressOf(it.scriptPubkey.toBytes(), h.w.chain) }.orEmpty(), out?.value?.toSat()?.toLong() ?: 0, psbt.fee().toLong(), satVb, replaces = txid)
        }
    }

    /** The keys this phone holds for [w]: its own phrase, or the seed wallet that cosigns a vault. Blocking (Keystore). */
    private fun signers(w: OnchainWallet): List<SignersContainer>? {
        val seedId = if (w.seed) w.id else w.signer ?: return null
        if (w.keyOnly) return store.privateKey(w.id)?.let { k -> Descriptors.xprvSecret(k, w.script ?: return null, w.chain).map { SignersContainer.fromDescriptor(it) } }
        val (words, pass) = store.secret(seedId) ?: return null
        val ds = if (w.multisig) Descriptors.multisigSecret(w.descriptors ?: return null, words, pass, w.chain) else Descriptors.secret(words, pass, w.script ?: return null, w.chain)
        return ds?.map { SignersContainer.fromDescriptor(it) }
    }

    /**
     * Signs a draft with this phone's key and broadcasts it, or hands back the PSBT when other signatures are needed.
     * With [payjoin] (the receiver's `pj=`) the signed draft is offered to the receiver first, and their payjoin goes out
     * instead when it checks out; otherwise the draft does.
     */
    suspend fun send(draft: OnchainDraft, payjoin: String? = null, onRun: (PayjoinRun) -> Unit = {}): SendOutcome = withContext(Dispatchers.IO) {
        val h = handle(draft.walletId)
        val psbt = Psbt(draft.psbt)
        val keys = signers(h.w)
        val done = h.lock.withLock { keys != null && h.bdk!!.signWithSigners(psbt, keys) }
        if (!done) return@withContext SendOutcome.NeedsSignatures(psbt.serialize())
        val tried = payjoin != null && draft.replaces == null && canPayjoin(h.w)
        var run = PayjoinRun(PayjoinRun.Step.SEND)
        fun emit(f: (PayjoinRun) -> PayjoinRun) { run = f(run); onRun(run) }
        val joined = if (!tried) null else runCatching { payjoin(h, psbt, Payjoin.uri(draft.address, draft.amount, payjoin!!), keys!!, ::emit) }
            .onFailure { e ->
                Trail.add("onchain", "payjoin skipped: " + ((e as? Payjoin.Rejected)?.message ?: e.javaClass.simpleName))
                emit { it.copy(failedAt = it.step, why = PayjoinRun.why(e)) }
            }.getOrNull()
        // The receiver may broadcast the original itself, so when the payjoin doesn't go out the original must.
        val joinedTxid = joined?.let { runCatching { broadcast(h, it.first) }.getOrNull() }?.also { Trail.add("onchain", "payjoin sent") }
        if (joined != null && joinedTxid == null) emit { it.copy(failedAt = PayjoinRun.Step.BROADCAST, why = PayjoinRun.Why.OFFLINE) }
        val txid = joinedTxid ?: broadcast(h, psbt)
        // A payjoin's payment output also holds the receiver's coins: what we sent is only what we asked to.
        if (joinedTxid != null) {
            cache.edit().putString("pj:$txid", draft.amount.toString()).apply()
            h.lock.withLock { publish(h) }
        }
        val extra = if (joinedTxid != null) joined.second else 0
        if (tried) emit { it.copy(step = PayjoinRun.Step.DONE, txid = txid, joined = joinedTxid != null, extraFee = extra) }
        SendOutcome.Sent(txid, if (tried) joinedTxid != null else null, extra)
    }

    /**
     * Offers [original] (signed, finalised) to the receiver of [bip21]; their proposal signed and what it took from our
     * change, or throws. Each step goes to [emit] as it starts.
     */
    private suspend fun payjoin(h: Handle, original: Psbt, bip21: String, keys: List<SignersContainer>, emit: ((PayjoinRun) -> PayjoinRun) -> Unit): Pair<Psbt, Long> {
        val bdk = h.bdk!!
        val (offer, flow) = h.lock.withLock { Payjoin.Offer(original, bdk::isMine, h.w.script?.let(Payjoin::inputVbytes)).let { it to it.flow() } }
        emit { it.copy(step = PayjoinRun.Step.SEND, flow = flow) }
        val proposal = Payjoin.propose(http, bip21, offer) { relay, directory ->
            emit { it.copy(step = PayjoinRun.Step.WAIT, relay = relay, directory = directory, deadline = System.currentTimeMillis() + Payjoin.WAIT_MS) }
        }
        emit { it.copy(step = PayjoinRun.Step.CHECK) }
        return h.lock.withLock {
            val p = offer.accept(proposal)
            if (!bdk.signWithSigners(p, keys, Payjoin.SIGN)) throw Payjoin.Rejected("unsigned")
            offer.checkRate(p)
            emit { it.copy(step = PayjoinRun.Step.BROADCAST, flow = offer.flow(p), extraFee = offer.contribution) }
            p to offer.contribution
        }
    }

    private val _payjoin = MutableStateFlow<PayjoinRun?>(null)
    /** The payjoin [startPayjoin] runs, step by step; null before the first. */
    val payjoinRun: StateFlow<PayjoinRun?> = _payjoin

    /**
     * Sends [draft] as a payjoin to [pj], on the app's scope so it finishes (or falls back to the original) even if
     * its screen goes away; [payjoinRun] follows it.
     */
    fun startPayjoin(draft: OnchainDraft, pj: String) {
        _payjoin.value = PayjoinRun()
        scope.launch {
            runCatching { send(draft, pj) { r -> _payjoin.value = r } }
                .onFailure { e -> _payjoin.update { (it ?: PayjoinRun()).copy(error = e) } }
        }
    }

    /** Whether a send from [w] can be a payjoin: it needs this phone to sign alone, twice. */
    fun canPayjoin(w: OnchainWallet) = !w.watchOnly && !w.multisig

    /** Adds this phone's signature to [psbt] (base64), if it holds a key for the wallet. */
    suspend fun sign(id: String, psbt: String): String = withContext(Dispatchers.IO) {
        val h = handle(id)
        val keys = signers(h.w) ?: return@withContext psbt
        val p = Psbt(psbt)
        h.lock.withLock { runCatching { h.bdk!!.signWithSigners(p, keys) } }
        p.serialize()
    }

    /** Merges the signatures of two copies of the same PSBT. Throws WRONG_WALLET when they aren't the same tx. */
    fun combine(a: String, b: String): String = runCatching { Psbt(a).combine(Psbt(b)).serialize() }.getOrElse { throw OnchainException(OnchainException.Reason.WRONG_WALLET) }

    fun status(id: String, psbt: String): PsbtStatus {
        val h = handle(id)
        val p = Psbt(psbt)
        val required = h.w.quorum?.first ?: 1
        val signed = p.input().minOfOrNull { i ->
            if (i.finalScriptWitness != null || i.finalScriptSig != null || i.tapKeySig != null) required else i.partialSigs.size
        } ?: 0
        val bdk = h.bdk
        val sends = p.extractTxUncheckedFeeRate().output().filter { bdk == null || !bdk.isMine(it.scriptPubkey) }
            .map { (addressOf(it.scriptPubkey.toBytes(), h.w.chain) ?: "?") to it.value.toSat().toLong() }
        return PsbtStatus(signed, required, runCatching { p.fee().toLong() }.getOrDefault(0), sends)
    }

    /** Finalises and broadcasts a fully signed PSBT. */
    suspend fun broadcast(id: String, psbt: String): String = withContext(Dispatchers.IO) { broadcast(handle(id), Psbt(psbt)) }

    private suspend fun broadcast(h: Handle, psbt: Psbt): String = h.lock.withLock {
        val bdk = h.bdk ?: throw OnchainException(OnchainException.Reason.NO_KEY)
        if (psbt.input().any { it.finalScriptWitness == null && it.finalScriptSig == null } && !bdk.finalizePsbt(psbt)) throw OnchainException(OnchainException.Reason.NO_KEY)
        val tx = psbt.extractTx()
        val txid = try { client(h.w.chain).transactionBroadcast(tx).toString() } catch (e: Exception) { clients.remove(h.w.chain)?.close(); throw e }
        // Shown at once as pending; the subscription confirms it.
        bdk.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, (System.currentTimeMillis() / 1000).toULong())))
        bdk.persist(h.persister!!)
        publish(h)
        Trail.add("onchain", "broadcast ok")
        txid
    }

    companion object {
        /** BIP-44 gap limit: addresses past this many unused in a row aren't found by restoring wallets. */
        const val GAP = 20
        // Small: electrs-esplora servers drop batches over ~20 requests.
        private const val BATCH = 10uL
        /** Many nodes still relay nothing under 1 sat/vB. */
        const val MIN_RATE = 1.0
        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

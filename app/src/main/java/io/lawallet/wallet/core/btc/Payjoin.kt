package io.lawallet.wallet.core.btc

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Script
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Payjoin sender. BIP-77 (v2: `pj=https://<directory>/<mailbox>#RK1…`, e.g. Bull Bitcoin) goes through the Payjoin Dev
 * Kit (`org.payjoindevkit`, built by scripts/build-payjoin-ffi.sh): the original is encrypted to the receiver's
 * mailbox through an OHTTP relay, and the proposal is polled for. BIP-78 (v1) endpoints get a plain POST, here: the
 * PDK bindings have no v1 sender, and BIP-77 directories no longer take one.
 *
 * Either way, output substitution is off: the receiver can add inputs and outputs and pay itself more, never less,
 * and take at most [Offer.maxFee] from our change towards the fee ([Offer.accept] checks it, on top of the PDK's own
 * checks). Anything else is [Rejected], and the caller sends the original instead.
 */
object Payjoin {
    class Rejected(what: String) : Exception(what)

    /** BIP-77 directories that relay OHTTP too; a request never goes through its own directory, which would see who sent it. */
    private val RELAYS = listOf("https://pj.bullbitcoin.com", "https://pj.bobspacebkk.com", "https://payjoin.lab.vinteum.org", "https://pj.pow.plan-b.foundation")

    /** How long the receiver has to answer, from the first request. */
    const val WAIT_MS = 60_000L

    /** The request as the PDK reads it: [pj] as given (its `#` fragment escaped), for [sats] to [address]. */
    fun uri(address: String, sats: Long, pj: String) =
        "bitcoin:$address?amount=${BigDecimal.valueOf(sats, 8).stripTrailingZeros().toPlainString()}&pj=${pj.replace("#", "%23")}"

    /**
     * The receiver's proposal for [offer], over BIP-77 when [bip21]'s endpoint is one, else BIP-78. Blocking.
     * [onSent]: the offer is out, through [relay] (BIP-77) to the receiver's [directory] host; now it's their turn.
     */
    fun propose(http: OkHttpClient, bip21: String, offer: Offer, onSent: (relay: String?, directory: String) -> Unit = { _, _ -> }): Psbt {
        val client = http.newBuilder().readTimeout(45, TimeUnit.SECONDS).callTimeout(WAIT_MS, TimeUnit.MILLISECONDS).build()
        val pjUri = runCatching { org.payjoindevkit.Uri.parse(bip21).checkPjSupported() }.getOrElse { throw Rejected("uri") }
        val builder = try {
            org.payjoindevkit.SenderBuilder(offer.psbt.serialize(), pjUri)
        } catch (_: org.payjoindevkit.SenderInputException.UnsupportedPjVersion) {
            val endpoint = endpoint(pjUri.pjEndpoint()) ?: throw Rejected("endpoint")
            onSent(null, endpoint.host)
            return request(client, endpoint, offer)
        }
        return try { v2(client, builder, pjUri.pjEndpoint(), offer, onSent) } catch (e: org.payjoindevkit.ResponseException) { throw Rejected(reason(e)) }
    }

    /** The receiver's error, by its BIP-78 code only (the message is theirs, and free text). */
    private fun reason(e: org.payjoindevkit.ResponseException) = when (e) {
        is org.payjoindevkit.ResponseException.WellKnown -> "receiver: " + e.v1.code().name.lowercase()
        is org.payjoindevkit.ResponseException.Validation -> "proposal invalid"
        is org.payjoindevkit.ResponseException.Unrecognized -> "receiver: unrecognized"
    }

    private fun v2(http: OkHttpClient, builder: org.payjoindevkit.SenderBuilder, directory: String, offer: Offer, onSent: (String?, String) -> Unit): Psbt {
        val dirHost = directory.toHttpUrlOrNull()?.host
        val relay = RELAYS.filter { it.toHttpUrlOrNull()?.host != dirHost }.random()
        val persister = Memory()
        // sat/vB → sat/kwu. The receiver may take our fee contribution from the same change output Offer.accept allows.
        val minRate = (offer.rate * 250).toLong().toULong()
        val b = builder.alwaysDisableOutputSubstitution()
        val sender = (offer.feeOutput?.let { b.buildWithAdditionalFee(offer.maxFee.toULong(), it.toUByte(), minRate, false) } ?: b.buildNonIncentivizing(minRate)).save(persister)
        val end = System.currentTimeMillis() + WAIT_MS
        val post = sender.createV2PostRequest(relay)
        var polling = sender.processResponse(send(http, post.request), post.ohttpCtx).save(persister)
        onSent(relay.toHttpUrlOrNull()?.host, dirHost.orEmpty())
        // The directory holds each poll until the receiver answers or ~30 s pass.
        while (System.currentTimeMillis() < end) {
            val poll = polling.createPollRequest(relay)
            when (val o = polling.processResponse(send(http, poll.request), poll.ohttpCtx).save(persister)) {
                is org.payjoindevkit.PollingForProposalTransitionOutcome.Progress -> return Psbt(o.psbtBase64)
                is org.payjoindevkit.PollingForProposalTransitionOutcome.Stasis -> polling = o.inner
            }
        }
        throw Rejected("timeout")
    }

    private fun send(http: OkHttpClient, r: org.payjoindevkit.Request): ByteArray {
        val req = Request.Builder().url(r.url).post(r.body.toRequestBody(r.contentType.toMediaType())).build()
        http.newCall(req).execute().use { res ->
            if (res.code != 200) throw Rejected("http ${res.code}")
            return res.body.bytes()
        }
    }

    /** The PDK keeps its session here: a payjoin that doesn't finish while the screen waits isn't resumed. */
    private class Memory : org.payjoindevkit.JsonSenderSessionPersister {
        private val events = mutableListOf<String>()
        override fun save(event: String) { events += event }
        override fun load(): List<String> = events.toList()
        override fun closeSession() {}
    }

    /**
     * Signs the proposal: the receiver's inputs are finalised and BDK skips them, but it checks every input's amount,
     * and theirs only come with a witness UTXO. Trusting it is safe: our signatures commit to our own amounts, and
     * [Offer.accept] judges the proposal by our inputs and outputs alone.
     */
    val SIGN = SignOptions(trustWitnessUtxo = true, assumeHeight = null, allowAllSighashes = false, tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true)

    /** Where to POST: [pj] without its fragment (BIP-77's keys, unused by a BIP-78 sender). HTTPS only. */
    fun endpoint(pj: String): HttpUrl? = pj.substringBefore('#').toHttpUrlOrNull()?.takeIf { it.isHttps }

    /** BIP-78's well-known error codes: logged when the receiver sends one (nothing else from its reply is). */
    private val V1_CODES = setOf("unavailable", "not-enough-money", "version-unsupported", "original-psbt-rejected")
    private val V1_CODE = Regex(""""errorCode"\s*:\s*"([a-z-]+)"""")

    /** An input's size in vbytes, for the fee the receiver may take from our change to pay for theirs. */
    fun inputVbytes(s: ScriptType) = when (s) { ScriptType.P2WPKH -> 68; ScriptType.P2TR -> 58; ScriptType.P2SH_P2WPKH -> 91; ScriptType.P2PKH -> 148 }

    /** BIP-78: posts [offer] to [endpoint]; the receiver's proposal. */
    fun request(http: OkHttpClient, endpoint: HttpUrl, offer: Offer): Psbt {
        val req = Request.Builder().url(offer.url(endpoint)).post(offer.body().toRequestBody("text/plain".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (r.code != 200) throw Rejected(r.body.string().let { b -> V1_CODE.find(b)?.groupValues?.get(1)?.takeIf { it in V1_CODES }?.let { "receiver: $it" } } ?: "http ${r.code}")
            return runCatching { Psbt(r.body.string().trim()) }.getOrElse { throw Rejected("not a psbt") }
        }
    }

    /** What we offer: [psbt], signed and finalised, the transaction that's broadcast if the payjoin doesn't happen. */
    /** Where a transaction's money comes from and goes, in sats: [ours] and [theirs] are inputs. */
    data class Flow(val ours: List<Long>, val theirs: List<Long>, val payment: Long, val change: Long, val fee: Long)

    class Offer(val psbt: Psbt, private val isMine: (Script) -> Boolean, inputVbytes: Int?) {
        private val tx: Transaction = psbt.extractTx()
        private val fee = psbt.fee().toLong()
        /** Our fee rate, sat/vB: the payjoin's may not be lower. */
        val rate = fee.toDouble() / tx.vsize().toLong()
        /** Our change, which the receiver may take [maxFee] from to pay for the input it adds. */
        val feeOutput = tx.output().indexOfFirst { isMine(it.scriptPubkey) }.takeIf { it >= 0 && inputVbytes != null }
        val maxFee = if (feeOutput == null) 0L else ceil(rate * inputVbytes!!).toLong()
        /** What the accepted proposal takes from our change towards the fee (at most [maxFee]). */
        var contribution = 0L
            private set
        /** The accepted proposal's inputs, in sats: ours (as in the original), then the receiver's. */
        private var inputValues: Pair<List<Long>, List<Long>>? = null

        /** The original's money: our inputs, what the receiver gets, our change and the fee. */
        fun flow(): Flow = flow(tx, inputValues(psbt, tx).let { it to emptyList() }, fee)

        /** The same for the proposal [accept] returned (signed or not). */
        fun flow(joined: Psbt): Flow = flow(joined.extractTxUncheckedFeeRate(), inputValues ?: (inputValues(psbt, tx) to emptyList()), joined.fee().toLong())

        private fun flow(t: Transaction, ins: Pair<List<Long>, List<Long>>, fee: Long): Flow {
            val (mine, others) = t.output().partition { isMine(it.scriptPubkey) }
            return Flow(ins.first, ins.second, others.sumOf { it.value.toSat().toLong() }, mine.sumOf { it.value.toSat().toLong() }, fee)
        }

        private fun inputValues(p: Psbt, t: Transaction) = p.input().mapIndexed { i, d ->
            (d.witnessUtxo ?: d.nonWitnessUtxo?.output()?.getOrNull(t.input()[i].previousOutput.vout.toInt()))?.value?.toSat()?.toLong() ?: 0L
        }

        fun url(endpoint: HttpUrl): HttpUrl = endpoint.newBuilder().apply {
            addQueryParameter("v", "1")
            addQueryParameter("disableoutputsubstitution", "true")
            addQueryParameter("minfeerate", BigDecimal(rate).setScale(2, RoundingMode.DOWN).toPlainString())
            if (feeOutput != null) {
                addQueryParameter("additionalfeeoutputindex", feeOutput.toString())
                addQueryParameter("maxadditionalfeecontribution", maxFee.toString())
            }
        }.build()

        /** The original as the receiver gets it: the tx, our UTXOs and final scripts; no keypaths or xpubs. */
        fun body(): String {
            val maps = Maps.read(psbt)
            val ins = tx.input().size
            return Maps.write(maps.mapIndexed { i, m ->
                when {
                    i == 0 -> m.filter { it.type == 0 }
                    i <= ins -> m.filter { it.type in UTXO || it.type in FINAL }
                    else -> emptyList()
                }
            })
        }

        /**
         * Checks [proposal] (BIP-78's sender checklist, judged by what we spend) and returns it ready to sign: our
         * inputs as we built them, outputs bare (BDK re-adds our keypaths). Throws [Rejected].
         */
        fun accept(proposal: Psbt): Psbt {
            val p = proposal.extractTxUncheckedFeeRate()
            if (p.version() != tx.version() || p.lockTime() != tx.lockTime()) throw Rejected("version or locktime")
            val pIn = p.input()
            val oIn = tx.input()
            if (pIn.map { it.sequence }.distinct().size != 1 || pIn[0].sequence != oIn[0].sequence) throw Rejected("sequence")
            // Our inputs, all of them, in order; theirs finalised, with a UTXO, and not ours (we'd sign it).
            val data = proposal.input()
            val ours = IntArray(pIn.size) { -1 }
            var k = 0
            pIn.forEachIndexed { i, txin ->
                val d = data[i]
                val final = d.finalScriptWitness != null || d.finalScriptSig != null
                if (k < oIn.size && txin.previousOutput == oIn[k].previousOutput) {
                    if (final) throw Rejected("our input finalised")
                    ours[i] = k++
                } else {
                    val prev = d.witnessUtxo ?: d.nonWitnessUtxo?.output()?.getOrNull(txin.previousOutput.vout.toInt())
                    if (!final || prev == null || isMine(prev.scriptPubkey)) throw Rejected("receiver input")
                }
            }
            if (k != oIn.size) throw Rejected("missing input")
            val theirs = pIn.size - oIn.size
            // Everyone we paid gets at least as much; our change gives up at most maxFee, and only when they added an input.
            fun paid(t: Transaction) = t.output().groupBy({ it.scriptPubkey.toBytes().toList() }, { it.value.toSat().toLong() }).mapValues { it.value.sum() }
            val before = paid(tx)
            val after = paid(p)
            if (tx.output().any { !isMine(it.scriptPubkey) && (after[it.scriptPubkey.toBytes().toList()] ?: 0) < before.getValue(it.scriptPubkey.toBytes().toList()) }) throw Rejected("payment reduced")
            fun mine(t: Transaction) = t.output().filter { isMine(it.scriptPubkey) }.sumOf { it.value.toSat().toLong() }
            val contribution = mine(tx) - mine(p)
            val original = Maps.read(psbt)
            val restored = Psbt(Maps.write(Maps.read(proposal).mapIndexed { i, m ->
                when {
                    i == 0 -> m.filter { it.type == 0 }
                    i <= pIn.size -> ours[i - 1].let { o -> if (o < 0) m else original[1 + o].filter { it.type in UTXO } }
                    else -> emptyList()
                }
            }))
            val newFee = runCatching { restored.fee().toLong() }.getOrElse { throw Rejected("fee") }
            if (newFee < fee || contribution > (if (theirs == 0) 0 else maxFee) || contribution > newFee - fee) throw Rejected("fee")
            this.contribution = contribution.coerceAtLeast(0)
            val values = inputValues(restored, p)
            inputValues = values.filterIndexed { i, _ -> ours[i] >= 0 } to values.filterIndexed { i, _ -> ours[i] < 0 }
            return restored
        }

        /** After signing: the fee rate didn't drop below ours (one vbyte per input of slack: signature sizes vary). */
        fun checkRate(signed: Psbt) {
            val t = signed.extractTx()
            if (signed.fee().toLong() < rate * (t.vsize().toLong() - t.input().size)) throw Rejected("fee rate")
        }
    }

    private val UTXO = setOf(0, 1)
    private val FINAL = setOf(7, 8)

    private class Entry(val key: ByteArray, val value: ByteArray) { val type get() = key[0].toInt() and 0xff }

    /** A PSBT (v0) as its key-value maps: global, then one per input, then one per output. */
    private object Maps {
        private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

        fun read(psbt: Psbt): List<List<Entry>> {
            val b = Base64.getDecoder().decode(psbt.serialize())
            var i = MAGIC.size
            fun size(): Int {
                val f = b[i++].toInt() and 0xff
                val n = when (f) { 0xfd -> 2; 0xfe -> 4; 0xff -> 8; else -> return f }
                return (0 until n).fold(0L) { v, j -> v or ((b[i + j].toLong() and 0xff) shl (8 * j)) }.also { i += n }.toInt()
            }
            fun bytes(n: Int) = b.copyOfRange(i, i + n).also { i += n }
            val maps = mutableListOf<List<Entry>>()
            while (i < b.size) {
                val m = mutableListOf<Entry>()
                while (true) { val k = size().takeIf { it > 0 } ?: break; m += Entry(bytes(k), bytes(size())) }
                maps += m
            }
            return maps
        }

        fun write(maps: List<List<Entry>>): String {
            val out = ByteArrayOutputStream().apply { write(MAGIC) }
            fun size(n: Int) = when {
                n < 0xfd -> out.write(n)
                n <= 0xffff -> { out.write(0xfd); out.write(n and 0xff); out.write(n ushr 8) }
                else -> { out.write(0xfe); repeat(4) { out.write((n ushr (8 * it)) and 0xff) } }
            }
            maps.forEach { m -> m.forEach { size(it.key.size); out.write(it.key); size(it.value.size); out.write(it.value) }; out.write(0) }
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }
    }
}

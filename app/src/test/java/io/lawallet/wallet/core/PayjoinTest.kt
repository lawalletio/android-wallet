package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip21
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.Payjoin
import io.lawallet.wallet.core.btc.ScriptType
import org.bitcoindevkit.Amount
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.LockTime
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.ScriptAmount
import org.bitcoindevkit.SignersContainer
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.TxOrdering
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** BIP-78 sender: a real receiver wallet builds proposals; we sign the honest one and refuse the greedy ones. */
class PayjoinTest {
    private val chain = Chain.SIGNET
    private val abandon = ("abandon ".repeat(11) + "about").split(" ")
    private val zoo = ("zoo ".repeat(11) + "wrong").split(" ")

    private fun wallet(seed: List<String>): Pair<Wallet, List<SignersContainer>> {
        val p = Descriptors.fromPhrase(seed, null, ScriptType.P2WPKH, chain)
        val w = Wallet(Descriptor(p.external, chain.kind), Descriptor(p.internal, chain.kind), chain.bdk, Persister.newInMemory())
        return w to Descriptors.secret(seed, null, ScriptType.P2WPKH, chain).map { SignersContainer.fromDescriptor(it) }
    }

    /** A made-up unconfirmed transaction paying [sats] to the wallet's next address. */
    private fun fund(w: Wallet, sats: Long, fill: Byte) {
        val script = w.revealNextAddress(KeychainKind.EXTERNAL).address.scriptPubkey().toBytes()
        fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        val raw = le(2, 4) + byteArrayOf(1) + ByteArray(32) { fill } + le(0, 4) + byteArrayOf(0) + le(0xffffffffL, 4) +
            byteArrayOf(1) + le(sats, 8) + byteArrayOf(script.size.toByte()) + script + le(0, 4)
        w.applyUnconfirmedTxs(listOf(UnconfirmedTx(Transaction(raw), 1u)))
    }

    private val sender = wallet(abandon).also { fund(it.first, 100_000, 0x11) }
    private val receiver = wallet(zoo).also { fund(it.first, 50_000, 0x22) }
    private val pay = receiver.first.revealNextAddress(KeychainKind.EXTERNAL).address.scriptPubkey()

    private val original = TxBuilder().addRecipient(pay, Amount.fromSat(30_000u)).feeRate(FeeRate.fromSatPerVb(2u)).finish(sender.first)
        .also { assertTrue(sender.first.signWithSigners(it, sender.second)) }
    private val offer = Payjoin.Offer(original, sender.first::isMine, Payjoin.inputVbytes(ScriptType.P2WPKH))

    /** The receiver adds its coin, takes [take] from our change, pays [extra] more fee and keeps the rest (less [short]). */
    private fun proposal(take: Long = offer.maxFee, extra: Long = 10, short: Long = 0): Psbt {
        val (r, keys) = receiver
        val o = original.extractTx()
        val coin = r.listUnspent().single()
        val outs = o.output().map { out ->
            val v = out.value.toSat().toLong()
            ScriptAmount(out.scriptPubkey, Amount.fromSat((if (sender.first.isMine(out.scriptPubkey)) v - take else v + 50_000 - extra - short).toULong()))
        }
        val p = TxBuilder()
            .addForeignUtxoWithSequence(o.input()[0].previousOutput, original.input()[0].copy(finalScriptWitness = null, finalScriptSig = null), 272uL, o.input()[0].sequence)
            .addUtxo(coin.outpoint).manuallySelectedOnly().ordering(TxOrdering.UNTOUCHED)
            .setRecipients(outs).feeAbsolute(Amount.fromSat((original.fee().toLong() + take + extra + short).toULong()))
            .nlocktime(LockTime.Blocks(o.lockTime())).version(o.version())
            .finish(r)
        r.signWithSigners(p, keys, Payjoin.SIGN)
        assertTrue(p.input().any { it.finalScriptWitness != null })
        return Psbt(p.serialize())
    }

    @Test fun signsAnHonestPayjoin() {
        // The receiver gets the original stripped of keypaths.
        val body = Psbt(offer.body())
        assertTrue(body.output().all { it.bip32Derivation.isEmpty() } && body.input().all { it.finalScriptWitness != null })

        val p = offer.accept(proposal())
        assertTrue(sender.first.signWithSigners(p, sender.second, Payjoin.SIGN))
        offer.checkRate(p)
        assertEquals(offer.maxFee, offer.contribution)
        val tx = p.extractTx()
        assertEquals(2, tx.input().size)
        // We paid what we meant to, plus at most maxFee towards the receiver's input.
        val change = { t: Transaction -> t.output().filter { sender.first.isMine(it.scriptPubkey) }.sumOf { it.value.toSat().toLong() } }
        assertEquals(change(original.extractTx()) - offer.maxFee, change(tx))
        assertEquals(80_000 - 10L, tx.output().first { it.scriptPubkey.toBytes().contentEquals(pay.toBytes()) }.value.toSat().toLong())
    }

    @Test fun refusesGreedyProposals() {
        fun refused(p: Psbt, why: String) = try { offer.accept(p); fail("accepted: $why") } catch (e: Payjoin.Rejected) { assertEquals(why, e.message) }
        refused(proposal(take = offer.maxFee + 1_000), "fee")
        refused(proposal(short = 50_001), "payment reduced")
        refused(original, "our input finalised")
    }

    @Test fun readsBullBitcoinUris() {
        val b = Bip21.parse("bitcoin:tb1q6q6de88mj8qkg0q5lupmpfexwnqjsr4d2gvx2p?amount=0.00666666&pjos=0&pj=HTTPS://PAYJO.IN/TXJCGKTKXLUUZ%23EX1WKV8CEC-OH1QYPM59NK2LXXS4890SUAXXYT25Z2VAPHP0X7YEYCJXGWAG6UG9ZU6NQ-RK1Q0DJS3VVDXWQQTLQ8022QGXSX7ML9PHZ6EDSF6AKEWQG758JPS2EV")!!
        assertEquals(666_666L, b.sats)
        assertEquals("https://payjo.in/TXJCGKTKXLUUZ", Payjoin.endpoint(b.pj!!).toString())
        assertNull(Payjoin.endpoint("http://example.com/pj"))
        // The PDK gets the request back with its fragment escaped; an older `+`-separated fragment keeps its `+`.
        assertEquals("bitcoin:${b.address}?amount=0.00001&pj=HTTPS://PAYJO.IN/TXJCGKTKXLUUZ%23EX1WKV8CEC-OH1QYPM59NK2LXXS4890SUAXXYT25Z2VAPHP0X7YEYCJXGWAG6UG9ZU6NQ-RK1Q0DJS3VVDXWQQTLQ8022QGXSX7ML9PHZ6EDSF6AKEWQG758JPS2EV", Payjoin.uri(b.address, 1_000, b.pj!!))
        assertEquals("https://pj.example/ID#RK1Q+OH1Q", Bip21.parse("bitcoin:${b.address}?pj=https://pj.example/ID%23RK1Q+OH1Q")!!.pj)
    }
}

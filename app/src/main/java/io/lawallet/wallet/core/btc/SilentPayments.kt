package io.lawallet.wallet.core.btc

import fr.acinq.secp256k1.Secp256k1
import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.core.nostr.hexToBytes
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import org.bitcoindevkit.Amount
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Script
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet

/*
 * BIP-352 silent payments, sending side. An `sp1…` address is two public keys (scan, spend); the output paid is a
 * one-off taproot key only the recipient can find, derived from the private keys of the inputs we spend. So the
 * payment can be built only by a wallet whose keys are on this phone, and only once its inputs are chosen.
 */
object SilentPayment {
    class Address(val scan: ByteArray, val spend: ByteArray, val chain: Chain)

    /** One input we spend: its outpoint (txid as shown), and its private key (for taproot, the tweaked output key's). */
    class Input(val txid: String, val vout: Int, val key: ByteArray, val taproot: Boolean)

    private val secp get() = Secp256k1

    /** An `sp1…` (mainnet) or `tsp1…` (signet/testnet) address, or null. */
    fun parse(s: String): Address? {
        val lower = s.lowercase()
        val chain = when { lower.startsWith("sp1") -> Chain.MAINNET; lower.startsWith("tsp1") -> Chain.SIGNET; else -> return null }
        val words = runCatching { Bech32.decode(lower, bech32m = true).second }.getOrNull() ?: return null
        val version = words.firstOrNull() ?: return null
        val data = runCatching { Bech32.convertBits(words.copyOfRange(1, words.size), 5, 8, false) }.getOrNull()?.let { b -> ByteArray(b.size) { b[it].toByte() } } ?: return null
        // v0 is exactly two keys; later versions (up to 30) start with them, and v31 is reserved for a breaking change.
        if (version == 31 || data.size < 66 || (version == 0 && data.size != 66)) return null
        val scan = data.copyOfRange(0, 33)
        val spend = data.copyOfRange(33, 66)
        if (runCatching { secp.pubkeyParse(scan); secp.pubkeyParse(spend) }.isFailure) return null
        return Address(scan, spend, chain)
    }

    fun isAddress(s: String) = parse(s) != null

    /** The x-only key of output [k] (0 for the first output to this recipient) paid to [to] by a tx spending [inputs]. */
    fun outputKey(to: Address, inputs: List<Input>, k: Int = 0): ByteArray {
        require(inputs.isNotEmpty())
        // Sum of the input keys, each negated when it's a taproot key with an odd y.
        val a = inputs.map { i -> if (i.taproot && compressed(i.key)[0] == 3.toByte()) secp.privKeyNegate(i.key) else i.key }
            .reduce { acc, key -> secp.privKeyTweakAdd(acc, key) }
        val smallest = inputs.map { it.txid.hexToBytes().reversedArray() + le32(it.vout) }.minWith(::compareUnsigned)
        val inputHash = tagged("BIP0352/Inputs", smallest + compressed(a))
        val shared = secp.pubKeyCompress(secp.pubKeyTweakMul(secp.pubkeyParse(to.scan), secp.privKeyTweakMul(a, inputHash)))
        val t = tagged("BIP0352/SharedSecret", shared + be32(k))
        return secp.pubKeyCompress(secp.pubKeyTweakAdd(secp.pubkeyParse(to.spend), t)).copyOfRange(1, 33)
    }

    /** OP_1 <32 bytes>: the taproot output script for [xOnly]. */
    fun script(xOnly: ByteArray) = byteArrayOf(0x51, 0x20) + xOnly

    /** BIP-86 key-path tweak: the private key of the output key for internal private key [d]. */
    fun taprootTweak(d: ByteArray): ByteArray {
        val p = compressed(d)
        val even = if (p[0] == 3.toByte()) secp.privKeyNegate(d) else d
        return secp.privKeyTweakAdd(even, tagged("TapTweak", p.copyOfRange(1, 33)))
    }

    /**
     * Each of [psbt]'s inputs with its private key, derived from [root] (a seed's master key or an imported xprv) along
     * the key origin BDK put in the PSBT. Null when an input isn't ours, or isn't a kind BIP-352 counts (P2WSH multisig).
     */
    fun inputs(psbt: Psbt, root: DescriptorSecretKey): List<Input>? {
        val outpoints = psbt.extractTxUncheckedFeeRate().input().map { it.previousOutput }
        return psbt.input().zip(outpoints).map { (i, o) ->
            if (i.witnessScript != null) return null
            val tap = i.tapKeyOrigins.entries.singleOrNull()
            val (pub, source) = if (tap != null) tap.key to tap.value.keySource
            else i.bip32Derivation.entries.singleOrNull()?.let { it.key to it.value } ?: return null
            val d = root.derive(source.path).use { it.secretBytes() }
            val pubOf = compressed(d).toHex()
            if (if (tap != null) pubOf.substring(2) != pub else pubOf != pub) return null
            Input(o.txid.toString(), o.vout.toInt(), if (tap != null) taprootTweak(d) else d, tap != null)
        }
    }

    /**
     * An unsigned payment of [sats] (null: everything) to [to] from [wallet], whose keys derive from [root]. The first
     * build picks the inputs, paying a stand-in taproot output of the same size; the second pays the real output from
     * exactly those inputs, so the fee is the same. Null when the inputs' keys aren't [root]'s.
     */
    fun draft(wallet: Wallet, root: DescriptorSecretKey, to: Address, sats: Long?, rate: FeeRate): Psbt? {
        fun build(b: TxBuilder, out: Script) =
            (if (sats == null) b.drainTo(out) else b.addRecipient(out, Amount.fromSat(sats.toULong()))).feeRate(rate).finish(wallet)
        fun outpoints(p: Psbt) = p.extractTxUncheckedFeeRate().input().map { it.previousOutput }
        val first = build(if (sats == null) TxBuilder().drainWallet() else TxBuilder(), Script(script(to.spend.copyOfRange(1, 33))))
        val keys = inputs(first, root) ?: return null
        val chosen = outpoints(first)
        val second = build(TxBuilder().addUtxos(chosen).manuallySelectedOnly(), Script(script(outputKey(to, keys))))
        // The output is bound to the inputs: a different set would pay a key the recipient never finds.
        check(outpoints(second).map { "${it.txid}:${it.vout}" }.toSet() == chosen.map { "${it.txid}:${it.vout}" }.toSet())
        return second
    }

    private fun compressed(d: ByteArray) = secp.pubKeyCompress(secp.pubkeyCreate(d))
    private fun tagged(tag: String, msg: ByteArray) = sha256(tag.toByteArray()).let { sha256(it + it + msg) }
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun be32(v: Int) = le32(v).reversedArray()
    private fun compareUnsigned(x: ByteArray, y: ByteArray): Int {
        for (i in x.indices) (x[i].toInt() and 0xff).compareTo(y[i].toInt() and 0xff).let { if (it != 0) return it }
        return 0
    }
}

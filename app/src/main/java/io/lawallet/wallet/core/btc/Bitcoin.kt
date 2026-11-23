package io.lawallet.wallet.core.btc

import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.core.nostr.sha256
import java.math.BigInteger
import java.security.SecureRandom

/*
 * The Bitcoin standards an onchain wallet is made of, as far as the UI needs them: BIP39 phrases, the
 * BIP44/49/84/86 script types, the chains we support, and recognising what someone pastes (phrase,
 * xpub/ypub/zpub, output descriptor, address). Keys, descriptors and wallets are BDK's: Descriptors.kt.
 */

/** The chains an onchain wallet can live on. Signet is for testing: its coins are worthless. */
enum class Chain(val bdk: org.bitcoindevkit.Network, val coin: Int, val explorer: String, val electrum: String) {
    MAINNET(org.bitcoindevkit.Network.BITCOIN, 0, "https://mempool.space", "ssl://electrum.blockstream.info:50002"),
    SIGNET(org.bitcoindevkit.Network.SIGNET, 1, "https://mempool.space/signet", "ssl://mempool.space:60602");

    val kind get() = if (this == MAINNET) org.bitcoindevkit.NetworkKind.MAIN else org.bitcoindevkit.NetworkKind.TEST
    fun txUrl(txid: String) = "$explorer/tx/$txid"
}

/** Single-sig script types with their standard derivation purpose. Names are the industry's (not translated). */
enum class ScriptType(val purpose: Int, val label: String, val short: String) {
    P2WPKH(84, "Native SegWit", "bc1q…"),
    P2TR(86, "Taproot", "bc1p…"),
    P2SH_P2WPKH(49, "Nested SegWit", "3…"),
    P2PKH(44, "Legacy", "1…");

    /** How its addresses start on [chain]. */
    fun prefix(chain: Chain) = if (chain == Chain.MAINNET) short else when (this) { P2WPKH -> "tb1q…"; P2TR -> "tb1p…"; P2SH_P2WPKH -> "2…"; P2PKH -> "m… n…" }

    /** First account: m/84'/0'/0' on mainnet, m/84'/1'/0' on signet. */
    fun path(chain: Chain = Chain.MAINNET) = "m/$purpose'/${chain.coin}'/0'"
}

enum class PhraseProblem { LENGTH, UNKNOWN_WORD, CHECKSUM }

/** BIP39 over the English list (res/raw/bip39_english.txt). */
class Bip39(val words: List<String>) {
    init { require(words.size == 2048) }
    private val index = words.withIndex().associate { it.value to it.index }

    /** 12 words = 128 bits of entropy, 24 = 256. */
    fun generate(wordCount: Int = 12, random: SecureRandom = SecureRandom()): List<String> =
        fromEntropy(ByteArray(wordCount * 4 / 3).also(random::nextBytes))

    fun fromEntropy(entropy: ByteArray): List<String> {
        val bits = (entropy + sha256(entropy)).joinToString("") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }
        return bits.take(entropy.size * 8 + entropy.size / 4).chunked(11).map { words[it.toInt(2)] }
    }

    fun isWord(w: String) = w in index

    /** Null when [phrase] is a valid BIP39 mnemonic. */
    fun problem(phrase: List<String>): PhraseProblem? {
        if (phrase.any { it !in index }) return PhraseProblem.UNKNOWN_WORD
        if (phrase.size !in VALID_LENGTHS) return PhraseProblem.LENGTH
        val bits = phrase.joinToString("") { index.getValue(it).toString(2).padStart(11, '0') }
        val entropy = bits.take(bits.length * 32 / 33).chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        return if (fromEntropy(entropy) == phrase) null else PhraseProblem.CHECKSUM
    }

    /** Words starting with [prefix], for autocomplete. */
    fun suggest(prefix: String, max: Int = 4): List<String> =
        if (prefix.isEmpty()) emptyList() else words.asSequence().filter { it.startsWith(prefix) }.take(max).toList()

    companion object { val VALID_LENGTHS = setOf(12, 15, 18, 21, 24) }
}

internal object Base58 {
    private const val A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Base58Check payload (checksum verified and stripped), or null. */
    fun decodeCheck(s: String): ByteArray? {
        if (s.isEmpty() || s.any { it !in A }) return null
        var n = BigInteger.ZERO
        for (c in s) n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(A.indexOf(c).toLong()))
        val tail = if (n.signum() == 0) ByteArray(0) else n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val bytes = ByteArray(s.takeWhile { it == '1' }.length) + tail
        if (bytes.size < 5) return null
        val payload = bytes.copyOfRange(0, bytes.size - 4)
        return payload.takeIf { sha256(sha256(it)).copyOfRange(0, 4).contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size)) }
    }

    fun encodeCheck(payload: ByteArray): String {
        val data = payload + sha256(sha256(payload)).copyOfRange(0, 4)
        var n = BigInteger(1, data)
        val b58 = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(b58)
            sb.append(A[r.toInt()])
            n = q
        }
        repeat(data.takeWhile { it == 0.toByte() }.size) { sb.append('1') }
        return sb.reverse().toString()
    }
}

enum class WatchKind { XPUB, DESCRIPTOR, ADDRESS, MULTISIG }
enum class Unsupported { PRIVATE_KEY, TESTNET, MULTISIG, INVALID }

/** What an import field holds. */
sealed interface BtcInput {
    /** A recovery phrase (possibly still being typed); [problem] is null once it checks out. */
    data class Phrase(val words: List<String>, val problem: PhraseProblem?) : BtcInput
    /**
     * Watch-only material on [chain]. [script] is null when the input doesn't say (a P2SH/P2WSH address, a multisig
     * descriptor). [value] is what was given, with `bitcoin:`, a query and whitespace stripped.
     */
    data class WatchOnly(val kind: WatchKind, val value: String, val script: ScriptType?, val chain: Chain = Chain.MAINNET) : BtcInput
    data class Rejected(val reason: Unsupported) : BtcInput
}

/** An extended public key, decoded: re-encoded as xpub/tpub (what descriptors take), plus what its SLIP-132 prefix said. */
data class ExtendedKey(val xpub: String, val chain: Chain, val script: ScriptType?, val multisig: Boolean)

object BtcImport {
    // SLIP-132 versions: xpub/tpub are ambiguous in practice; like BlueWallet we read them as BIP44 (the user can change it).
    private val PUBLIC = mapOf(
        0x0488B21E to (Chain.MAINNET to ScriptType.P2PKH), 0x049D7CB2 to (Chain.MAINNET to ScriptType.P2SH_P2WPKH), 0x04B24746 to (Chain.MAINNET to ScriptType.P2WPKH),
        0x043587CF to (Chain.SIGNET to ScriptType.P2PKH), 0x044A5262 to (Chain.SIGNET to ScriptType.P2SH_P2WPKH), 0x045F1CF6 to (Chain.SIGNET to ScriptType.P2WPKH),
    )
    private val PRIVATE = setOf(0x0488ADE4, 0x049D7878, 0x04B2430C, 0x04358394, 0x044A4E28, 0x045F18BC, 0x0295B005, 0x02AA7A99, 0x024285B5, 0x02575048)
    // Ypub, Zpub (mainnet), Upub, Vpub (testnet/signet): multisig cosigner keys.
    private val MULTISIG = mapOf(0x0295B43F to Chain.MAINNET, 0x02AA7ED3 to Chain.MAINNET, 0x024289EF to Chain.SIGNET, 0x02575483 to Chain.SIGNET)
    private const val XPUB = 0x0488B21E
    private const val TPUB = 0x043587CF
    private val KEY = Regex("[xtyzuvYZUV]pub[1-9A-HJ-NP-Za-km-z]{100,}")

    /** Null when nothing is recognisable yet (empty, or not Bitcoin at all). */
    fun detect(raw: String, bip39: Bip39): BtcInput? {
        val s = raw.trim().let { if (it.startsWith("bitcoin:", ignoreCase = true)) it.substring(8).substringBefore('?') else it }
        if (s.isEmpty()) return null
        val words = s.lowercase().split(Regex("\\s+"))
        // Two words or more, or one short lowercase word (the first one being typed), is a phrase.
        if (words.size > 1 || (s.length <= 8 && s.all { it in 'a'..'z' })) return BtcInput.Phrase(words, bip39.problem(words))
        if ('(' in s) return descriptor(s)
        extended(s)?.let { return it }
        return address(s)
    }

    private fun payload(key: String) = Base58.decodeCheck(key)?.takeIf { it.size == 78 }
    private fun version(p: ByteArray) = (0..3).fold(0) { acc, i -> (acc shl 8) or (p[i].toInt() and 0xff) }

    /** Any SLIP-132 public key (xpub, ypub, zpub, Zpub, tpub, vpub…) as xpub/tpub, or null if [key] isn't one. */
    fun extendedKey(key: String): ExtendedKey? {
        val p = payload(key.trim()) ?: return null
        val v = version(p)
        val pub = PUBLIC[v]
        val chain = pub?.first ?: MULTISIG[v] ?: return null
        val target = if (chain == Chain.MAINNET) XPUB else TPUB
        val out = byteArrayOf((target ushr 24).toByte(), (target ushr 16).toByte(), (target ushr 8).toByte(), target.toByte()) + p.copyOfRange(4, 78)
        return ExtendedKey(Base58.encodeCheck(out), chain, pub?.second, pub == null)
    }

    /** Every SLIP-132 key in [s] rewritten as xpub/tpub, so a Sparrow or Coldcard descriptor with zpubs parses in BDK. */
    fun normalizeKeys(s: String): String = KEY.replace(s) { extendedKey(it.value)?.xpub ?: it.value }

    private fun extended(s: String): BtcInput? {
        val p = payload(s) ?: return null
        if (version(p) in PRIVATE) return BtcInput.Rejected(Unsupported.PRIVATE_KEY)
        val k = extendedKey(s) ?: return BtcInput.Rejected(Unsupported.INVALID)
        // A Zpub/Vpub is one cosigner of a multisig vault, not a wallet by itself.
        if (k.multisig) return BtcInput.Rejected(Unsupported.MULTISIG)
        return BtcInput.WatchOnly(WatchKind.XPUB, s, k.script, k.chain)
    }

    /** Recognised by shape only; BDK validates it (and its #checksum) when the wallet is made. */
    private fun descriptor(s: String): BtcInput {
        val clean = s.replace(Regex("\\s"), "")
        val body = clean.substringBefore('#')
        if (Regex("[xtyzuvYZUV]prv").containsMatchIn(body)) return BtcInput.Rejected(Unsupported.PRIVATE_KEY)
        val keys = KEY.findAll(body).map { it.value }.toList()
        if (keys.isEmpty()) return BtcInput.Rejected(Unsupported.INVALID)
        val chain = keys.map { extendedKey(it)?.chain ?: return BtcInput.Rejected(Unsupported.INVALID) }.toSet().singleOrNull()
            ?: return BtcInput.Rejected(Unsupported.INVALID)
        if ("multi(" in body) return BtcInput.WatchOnly(WatchKind.MULTISIG, clean, null, chain)
        val script = when {
            body.startsWith("wpkh(") -> ScriptType.P2WPKH
            body.startsWith("tr(") -> ScriptType.P2TR
            body.startsWith("sh(wpkh(") -> ScriptType.P2SH_P2WPKH
            body.startsWith("pkh(") -> ScriptType.P2PKH
            else -> return BtcInput.Rejected(Unsupported.INVALID)
        }
        return BtcInput.WatchOnly(WatchKind.DESCRIPTOR, clean, script, chain)
    }

    private fun address(s: String): BtcInput? {
        val lower = s.lowercase()
        if (lower.startsWith("bcrt1")) return BtcInput.Rejected(Unsupported.TESTNET)
        val segwit = when { lower.startsWith("bc1") -> Chain.MAINNET; lower.startsWith("tb1") -> Chain.SIGNET; else -> null }
        if (segwit != null) {
            // Witness v0 uses bech32, v1+ bech32m (BIP350).
            val data = runCatching { Bech32.decode(s) }.getOrNull()?.second?.takeIf { it.firstOrNull() == 0 }
                ?: runCatching { Bech32.decode(s, bech32m = true) }.getOrNull()?.second?.takeIf { (it.firstOrNull() ?: -1) in 1..16 }
                ?: return BtcInput.Rejected(Unsupported.INVALID)
            val program = runCatching { Bech32.convertBits(data.copyOfRange(1, data.size), 5, 8, false) }.getOrNull() ?: return BtcInput.Rejected(Unsupported.INVALID)
            val script = when {
                data[0] == 0 && program.size == 20 -> ScriptType.P2WPKH
                data[0] == 0 && program.size == 32 -> null // P2WSH
                data[0] == 1 && program.size == 32 -> ScriptType.P2TR
                else -> return BtcInput.Rejected(Unsupported.INVALID)
            }
            return BtcInput.WatchOnly(WatchKind.ADDRESS, lower, script, segwit)
        }
        val p = Base58.decodeCheck(s)?.takeIf { it.size == 21 } ?: return null
        return when (p[0].toInt() and 0xff) {
            0x00 -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, ScriptType.P2PKH)
            0x05 -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, null) // P2SH: could be anything
            0x6f -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, ScriptType.P2PKH, Chain.SIGNET)
            0xc4 -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, null, Chain.SIGNET)
            else -> null
        }
    }
}

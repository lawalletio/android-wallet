package io.lawallet.wallet.core.btc

import io.lawallet.wallet.core.nostr.Bech32
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * The Bitcoin standards an onchain wallet is made of, as far as the UI needs them: BIP39 phrases, the
 * BIP44/49/84/86 script types, and recognising what someone pastes (phrase, xpub/ypub/zpub, output
 * descriptor, address). No key derivation here yet: this backs the onchain UI only.
 */

/** Single-sig script types with their standard derivation purpose. Names are the industry's (not translated). */
enum class ScriptType(val purpose: Int, val label: String, val short: String) {
    P2WPKH(84, "Native SegWit", "bc1q…"),
    P2TR(86, "Taproot", "bc1p…"),
    P2SH_P2WPKH(49, "Nested SegWit", "3…"),
    P2PKH(44, "Legacy", "1…");

    /** First account, mainnet: m/84'/0'/0'. */
    val path get() = "m/$purpose'/0'/0'"
}

private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

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
}

enum class WatchKind { XPUB, DESCRIPTOR, ADDRESS }
enum class Unsupported { PRIVATE_KEY, TESTNET, MULTISIG, INVALID }

/** What an import field holds. */
sealed interface BtcInput {
    /** A recovery phrase (possibly still being typed); [problem] is null once it checks out. */
    data class Phrase(val words: List<String>, val problem: PhraseProblem?) : BtcInput
    /** Watch-only material. [script] is null when the input doesn't say (e.g. a P2SH/P2WSH address). */
    data class WatchOnly(val kind: WatchKind, val value: String, val script: ScriptType?) : BtcInput
    data class Rejected(val reason: Unsupported) : BtcInput
}

object BtcImport {
    // SLIP-132 versions: xpub is ambiguous in practice; like BlueWallet we read it as BIP44 (the user can change it).
    private val PUBLIC = mapOf(0x0488B21E to ScriptType.P2PKH, 0x049D7CB2 to ScriptType.P2SH_P2WPKH, 0x04B24746 to ScriptType.P2WPKH)
    private val TESTNET_PUB = setOf(0x043587CF, 0x044A5262, 0x045F1CF6)
    private val PRIVATE = setOf(0x0488ADE4, 0x049D7878, 0x04B2430C, 0x04358394, 0x044A4E28, 0x045F18BC)
    private val MULTISIG = setOf(0x0295B43F, 0x02AA7ED3, 0x024289EF, 0x02575483) // Ypub, Zpub, Upub, Vpub

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

    private fun version(key: String): Int? {
        val p = Base58.decodeCheck(key)?.takeIf { it.size == 78 } ?: return null
        return (0..3).fold(0) { acc, i -> (acc shl 8) or (p[i].toInt() and 0xff) }
    }

    private fun extended(s: String): BtcInput? {
        val v = version(s) ?: return null
        return when (v) {
            in PUBLIC -> BtcInput.WatchOnly(WatchKind.XPUB, s, PUBLIC.getValue(v))
            in PRIVATE -> BtcInput.Rejected(Unsupported.PRIVATE_KEY)
            in TESTNET_PUB -> BtcInput.Rejected(Unsupported.TESTNET)
            in MULTISIG -> BtcInput.Rejected(Unsupported.MULTISIG)
            else -> BtcInput.Rejected(Unsupported.INVALID)
        }
    }

    // ponytail: the BIP380 #checksum isn't verified; add it when descriptors are used to derive addresses.
    private fun descriptor(s: String): BtcInput {
        val body = s.substringBefore('#').replace(" ", "")
        if (Regex("[xtyzuv]prv").containsMatchIn(body)) return BtcInput.Rejected(Unsupported.PRIVATE_KEY)
        if ("multi(" in body || body.startsWith("wsh(") || body.startsWith("sh(wsh(")) return BtcInput.Rejected(Unsupported.MULTISIG)
        val script = when {
            body.startsWith("wpkh(") -> ScriptType.P2WPKH
            body.startsWith("tr(") -> ScriptType.P2TR
            body.startsWith("sh(wpkh(") -> ScriptType.P2SH_P2WPKH
            body.startsWith("pkh(") -> ScriptType.P2PKH
            else -> return BtcInput.Rejected(Unsupported.INVALID)
        }
        val key = Regex("[xt]pub[1-9A-HJ-NP-Za-km-z]{100,}").find(body)?.value ?: return BtcInput.Rejected(Unsupported.INVALID)
        return when (version(key)) {
            0x0488B21E -> BtcInput.WatchOnly(WatchKind.DESCRIPTOR, s, script)
            in TESTNET_PUB -> BtcInput.Rejected(Unsupported.TESTNET)
            else -> BtcInput.Rejected(Unsupported.INVALID)
        }
    }

    private fun address(s: String): BtcInput? {
        val lower = s.lowercase()
        if (lower.startsWith("tb1") || lower.startsWith("bcrt1")) return BtcInput.Rejected(Unsupported.TESTNET)
        if (lower.startsWith("bc1")) {
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
            return BtcInput.WatchOnly(WatchKind.ADDRESS, lower, script)
        }
        val p = Base58.decodeCheck(s)?.takeIf { it.size == 21 } ?: return null
        return when (p[0].toInt() and 0xff) {
            0x00 -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, ScriptType.P2PKH)
            0x05 -> BtcInput.WatchOnly(WatchKind.ADDRESS, s, null) // P2SH: could be anything
            0x6f, 0xc4 -> BtcInput.Rejected(Unsupported.TESTNET)
            else -> null
        }
    }
}

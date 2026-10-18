package io.lawallet.wallet.core.nostr

/** BIP-173 bech32 (not bech32m) — what npub/nsec/lnurl/bolt11 all use. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c -> out[i] = c.code ushr 5; out[i + hrp.length + 1] = c.code and 31 }
        }

    fun encode(hrp: String, words: IntArray): String {
        val values = hrpExpand(hrp) + words + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (words + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** Returns hrp + 5-bit words (checksum stripped). No length limit (bolt11/lnurl exceed 90). */
    fun decode(input: String): Pair<String, IntArray> {
        val s = input.trim()
        require(s == s.lowercase() || s == s.uppercase()) { "mixed case" }
        val lower = s.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "invalid bech32" }
        val hrp = lower.substring(0, pos)
        val data = IntArray(lower.length - pos - 1) {
            CHARSET.indexOf(lower[pos + 1 + it]).also { v -> require(v >= 0) { "invalid char" } }
        }
        require(polymod(hrpExpand(hrp) + data) == 1) { "bad checksum" }
        return hrp to data.copyOf(data.size - 6)
    }

    fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid padding" }
        }
        return out.toIntArray()
    }

    fun encodeBytes(hrp: String, bytes: ByteArray): String =
        encode(hrp, convertBits(bytes.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true))

    fun decodeBytes(input: String): Pair<String, ByteArray> {
        val (hrp, words) = decode(input)
        return hrp to convertBits(words, 5, 8, false).map { it.toByte() }.toByteArray()
    }
}

object Nip19 {
    fun npub(pubkeyHex: String) = Bech32.encodeBytes("npub", pubkeyHex.hexToBytes())
    fun nsec(secretHex: String) = Bech32.encodeBytes("nsec", secretHex.hexToBytes())

    /** Accepts nsec1… or 64-char hex; returns hex secret. */
    fun parseSecret(input: String): String {
        val s = input.trim()
        if (s.length == 64 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return s.lowercase()
        val (hrp, bytes) = Bech32.decodeBytes(s)
        require(hrp == "nsec" && bytes.size == 32) { "not an nsec" }
        return bytes.toHex()
    }

    fun decodeNpub(npub: String): String {
        val (hrp, bytes) = Bech32.decodeBytes(npub)
        require(hrp == "npub" && bytes.size == 32) { "not an npub" }
        return bytes.toHex()
    }
}

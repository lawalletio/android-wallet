package io.lawallet.wallet.core.btc

/**
 * Recovery phrases as QR codes ([SeedQR](https://github.com/SeedSigner/seedsigner/blob/dev/docs/seed_qr/README.md)):
 * - Standard: each word's BIP-39 index as 4 digits, 48 digits for 12 words, 96 for 24.
 * - Compact: the entropy itself (16 or 32 bytes) in a binary QR. The camera hands binary codes over as
 *   [BINARY]`<hex>`; a reader may give the payload, or the QR's byte-mode bit stream (mode, length, data).
 * - Plain text: the words themselves.
 * A phrase only counts when its BIP-39 checksum holds.
 */
object SeedQr {
    /** How the camera passes a binary (non-text) code on: this prefix, then its bytes in hex. */
    const val BINARY = "binary:"

    /** The phrase in [raw], or null when it isn't one; [label] says which form it came in. */
    fun read(raw: String, bip39: Bip39): Pair<List<String>, String>? {
        val s = raw.trim()
        val words: List<String>? = when {
            (s.length == 48 || s.length == 96) && s.all { it in '0'..'9' } ->
                s.chunked(4).map { it.toInt() }.takeIf { idx -> idx.all { it < 2048 } }?.map { bip39.words[it] }
            s.startsWith(BINARY) -> hex(s.removePrefix(BINARY))?.let(::entropy)?.let(bip39::fromEntropy)
            else -> s.lowercase().split(Regex("\\s+")).takeIf { it.size in Bip39.VALID_LENGTHS && it.all(bip39::isWord) }
        }
        val label = when { s.startsWith(BINARY) -> "Compact SeedQR"; s.first().isDigit() -> "SeedQR"; else -> null }
        return words?.takeIf { bip39.problem(it) == null }?.let { it to (label ?: "") }
    }

    /** 16 or 32 bytes of entropy: as they are, or out of a QR byte-mode segment (0100, an 8-bit length, the data). */
    internal fun entropy(b: ByteArray): ByteArray? {
        if (b.size == 16 || b.size == 32) return b
        if (b.size < 18 || (b[0].toInt() and 0xf0) != 0x40) return null
        val len = ((b[0].toInt() and 0x0f) shl 4) or ((b[1].toInt() and 0xff) ushr 4)
        if (len != 16 && len != 32 || b.size < len + 2) return null
        // The data starts 12 bits in: each byte straddles two of the stream's.
        return ByteArray(len) { i -> (((b[i + 1].toInt() and 0x0f) shl 4) or ((b[i + 2].toInt() and 0xff) ushr 4)).toByte() }
    }

    private fun hex(s: String) = runCatching { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
}

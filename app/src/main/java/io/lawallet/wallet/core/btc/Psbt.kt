package io.lawallet.wallet.core.btc

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import java.util.Base64

/**
 * PSBTs between signers, the ways Sparrow, Nunchuk, Keystone and Coldcard move them: base64 text, a binary `.psbt`
 * file, or an animated BC-UR QR (`ur:crypto-psbt`, fountain-coded so any frames in any order assemble it).
 */
object PsbtCodec {
    private const val TYPE = "crypto-psbt"
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

    /** Frames for an animated QR; call [UREncoder.nextPart] forever (fountain parts after the first pass). */
    fun encoder(base64: String, fragment: Int = 200): UREncoder = UREncoder(UR.fromBytes(TYPE, Base64.getDecoder().decode(base64)), fragment, 10, 0)

    /** A PSBT from pasted or shared text (base64 or hex) or a file's bytes; base64 out, or null when it isn't one. */
    fun parse(text: String): String? {
        val s = text.trim().replace(Regex("\\s"), "")
        val bytes = when {
            s.startsWith("cHNidP8") -> runCatching { Base64.getDecoder().decode(s) }.getOrNull()
            s.startsWith("70736274ff", ignoreCase = true) && s.length % 2 == 0 -> ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            else -> null
        }
        return bytes?.let(::fromBytes)
    }

    fun fromBytes(bytes: ByteArray): String? {
        if (bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return Base64.getEncoder().encodeToString(bytes)
        // A file may hold the base64 text instead of the binary.
        return runCatching { bytes.decodeToString() }.getOrNull()?.takeIf { it.trim().startsWith("cHNidP8") }?.let(::parse)
    }

    fun bytes(base64: String): ByteArray = Base64.getDecoder().decode(base64)
}

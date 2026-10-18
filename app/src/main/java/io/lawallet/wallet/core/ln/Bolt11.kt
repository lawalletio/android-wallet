package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.core.nostr.toHex

data class Bolt11(
    val raw: String,
    val network: String,
    val amountMsat: Long?,
    val timestamp: Long,
    val expirySeconds: Long,
    val paymentHash: String?,
    val description: String?,
) {
    val amountSats: Long? get() = amountMsat?.takeIf { it > 0 }?.let { it / 1000 }
    val expiresAtMs: Long get() = (timestamp + expirySeconds) * 1000

    companion object {
        private val HRP = Regex("^ln(bcrt|bc|tbs|tb)(\\d+)?([munp])?$")

        fun decode(invoice: String): Bolt11 {
            val raw = invoice.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
            val (hrp, words) = Bech32.decode(raw)
            val m = HRP.matchEntire(hrp) ?: throw IllegalArgumentException("not a bolt11 invoice")
            val amountMsat = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { digits ->
                val n = digits.toBigInteger()
                val msat = when (m.groupValues[3]) {
                    "" -> n * 100_000_000_000.toBigInteger()
                    "m" -> n * 100_000_000.toBigInteger()
                    "u" -> n * 100_000.toBigInteger()
                    "n" -> n * 100.toBigInteger()
                    "p" -> { require(n.mod(10.toBigInteger()).signum() == 0) { "sub-msat amount" }; n / 10.toBigInteger() }
                    else -> error("unreachable")
                }
                msat.toLong()
            }
            require(words.size > 7 + 104) { "invoice too short" }
            val data = words.copyOf(words.size - 104) // strip signature
            var timestamp = 0L
            for (i in 0 until 7) timestamp = (timestamp shl 5) or data[i].toLong()
            var expiry = 3600L
            var hash: String? = null
            var desc: String? = null
            var i = 7
            while (i + 3 <= data.size) {
                val type = data[i]
                val len = (data[i + 1] shl 5) or data[i + 2]
                val field = data.copyOfRange(i + 3, (i + 3 + len).coerceAtMost(data.size))
                i += 3 + len
                when (type) {
                    1 -> if (len == 52) hash = bytes(field).toHex()
                    13 -> desc = String(bytes(field))
                    6 -> expiry = field.fold(0L) { acc, w -> (acc shl 5) or w.toLong() }
                }
            }
            return Bolt11(raw, m.groupValues[1], amountMsat, timestamp, expiry, hash, desc)
        }

        /** 5-bit words → bytes, dropping trailing pad bits (lenient, like light-bolt11-decoder). */
        private fun bytes(words: IntArray): ByteArray {
            var acc = 0
            var bits = 0
            val out = java.io.ByteArrayOutputStream()
            for (w in words) {
                acc = (acc shl 5) or w
                bits += 5
                if (bits >= 8) { bits -= 8; out.write((acc ushr bits) and 0xff) }
            }
            return out.toByteArray()
        }
    }
}

package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.nostr.Bech32
import java.net.URLEncoder

/** Port of apps/web/lib/client/nwc/parse-destination.ts — pure, no IO. */
sealed interface Destination {
    data class Invoice(val bolt11: String, val amountSats: Long?, val description: String?, val paymentHash: String?, val expiresAtMs: Long?) : Destination
    data class LnurlPay(val lnurlpUrl: String, val address: String?, val username: String?, val host: String?) : Destination
    data class Npub(val npub: String) : Destination
}

class DestinationException(val code: String, message: String) : Exception(message)

object DestinationParser {
    private val LN_ADDRESS = Regex("^([a-z0-9._-]+)@([a-z0-9.-]+\\.[a-z]{2,})$", RegexOption.IGNORE_CASE)

    fun isLightningAddress(s: String) = LN_ADDRESS.matches(s.trim())

    fun parse(rawInput: String): Destination {
        val trimmed = rawInput.trim()
        val input = if (trimmed.lowercase().startsWith("lightning:")) trimmed.substring(10) else trimmed
        if (input.isEmpty()) throw DestinationException("empty", "Enter a recipient")
        val lower = input.lowercase()
        return when {
            Regex("^ln(bc|tb|bcrt)").containsMatchIn(lower) -> try {
                val inv = Bolt11.decode(input)
                Destination.Invoice(input, inv.amountSats, inv.description, inv.paymentHash, inv.expiresAtMs)
            } catch (e: Exception) {
                throw DestinationException("invalidInvoice", "Invalid Lightning invoice")
            }
            lower.startsWith("lnurl1") -> {
                val url = decodeLnurl(input) ?: throw DestinationException("invalidLnurl", "Invalid LNURL")
                Destination.LnurlPay(url, null, null, null)
            }
            LN_ADDRESS.matches(input) -> {
                val (user, host) = LN_ADDRESS.matchEntire(input)!!.destructured
                Destination.LnurlPay("https://$host/.well-known/lnurlp/${URLEncoder.encode(user, "UTF-8")}", input, user, host)
            }
            lower.startsWith("npub1") -> Destination.Npub(input)
            else -> throw DestinationException("unsupported", "Unsupported recipient. Use a Lightning address, bolt11 invoice, or LNURL.")
        }
    }

    fun decodeLnurl(lnurl: String): String? = runCatching {
        val url = String(Bech32.decodeBytes(lnurl).second)
        url.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }.getOrNull()
}

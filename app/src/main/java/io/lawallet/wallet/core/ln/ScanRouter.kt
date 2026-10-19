package io.lawallet.wallet.core.ln

/**
 * What a scanned/pasted/tapped payload means. Mirrors scan-screen.tsx's order:
 * activation URL → LNURL (network) → parseDestination.
 */
sealed interface ScanResult {
    data class Activate(val tokenId: String, val sameInstance: Boolean, val url: String) : ScanResult
    data class Withdraw(val params: WithdrawParams) : ScanResult
    data class Pay(val destination: Destination) : ScanResult
    data class Error(val code: String, val message: String) : ScanResult
}

class ScanRouter(private val lnurl: Lnurl) {
    /** [instanceHosts]: hosts that count as "this community" (transport URL + advertised endpoint). */
    suspend fun route(raw: String, instanceHosts: Set<String>): ScanResult {
        val text = raw.trim()
        ActivationUrl.parse(text)?.let { a ->
            return ScanResult.Activate(a.tokenId, instanceHosts.any { it.equals(a.host, ignoreCase = true) }, text)
        }
        if (Lnurl.looksLikeLnurl(text)) {
            return try {
                when (val r = lnurl.resolve(text)) {
                    is LnurlResolved.Withdraw -> ScanResult.Withdraw(r.params)
                    is LnurlResolved.Pay -> ScanResult.Pay(Destination.LnurlPay(r.lnurlpUrl, null, null, null))
                    null -> ScanResult.Error("unsupported", "Unsupported code")
                }
            } catch (e: LnurlException) {
                ScanResult.Error(e.code ?: "lnurl", e.message ?: "LNURL error")
            }
        }
        return try {
            ScanResult.Pay(DestinationParser.parse(text))
        } catch (e: DestinationException) {
            ScanResult.Error(e.code, e.message ?: "Unsupported code")
        }
    }
}

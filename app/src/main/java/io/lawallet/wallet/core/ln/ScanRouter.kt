package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.plugin.PluginSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * What a scanned/pasted/tapped payload means. Mirrors scan-screen.tsx's order:
 * activation URL → Cashu token → LNURL (network) → parseDestination.
 */
sealed interface ScanResult {
    data class Activate(val tokenId: String, val sameInstance: Boolean, val url: String) : ScanResult
    data class Withdraw(val params: WithdrawParams) : ScanResult
    data class Cashu(val token: CashuToken) : ScanResult
    data class Pay(val destination: Destination) : ScanResult
    /** A plugin to offer through Add Plugin (resolver + permissions sheet). Never installed straight from a scan. */
    data class InstallPlugin(val url: String) : ScanResult
    /** A plugin link in a build without plugins (`DEBUG || PLUGINS` is false). */
    data object PluginsUnavailable : ScanResult
    data class Error(val code: String, val message: String) : ScanResult
}

/** [plugins]: whether this build has the Plugins section (`BuildConfig.DEBUG || BuildConfig.PLUGINS`). */
class ScanRouter(private val lnurl: Lnurl, private val plugins: Boolean = false) {
    /** [instanceHosts]: hosts that count as "this community" (transport URL + advertised endpoint). */
    suspend fun route(raw: String, instanceHosts: Set<String>): ScanResult {
        val text = unwrapLightningParam(raw.trim())
        PluginLink.parse(text)?.let { link ->
            val url = link.url ?: return ScanResult.Error(PluginLink.BAD_URL, "Not a GitHub plugin link")
            return if (plugins) ScanResult.InstallPlugin(url) else ScanResult.PluginsUnavailable
        }
        ActivationUrl.parse(text)?.let { a ->
            return ScanResult.Activate(a.tokenId, instanceHosts.any { it.equals(a.host, ignoreCase = true) }, text)
        }
        CashuToken.parse(text)?.let { return ScanResult.Cashu(it) }
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

    companion object {
        /** Merchant terminals (e.g. Blink) encode `https://…?lightning=LNURL1…`; treat it as the bare LNURL. */
        fun unwrapLightningParam(text: String): String {
            val url = text.toHttpUrlOrNull() ?: return text
            val name = url.queryParameterNames.firstOrNull { it.equals("lightning", ignoreCase = true) } ?: return text
            return url.queryParameter(name)?.trim()?.takeIf { it.lowercase().startsWith("lnurl1") } ?: text
        }
    }
}

/**
 * A plugin install link: `lawallet:plugin?url=<urlencoded https://github.com/<o>/<r>/tree/<ref>/plugins/<name>>`, or that
 * GitHub folder URL on its own. The format is documented in docs/plugins.md and in lawalletio/mobile-plugins.
 */
data class PluginLink(val url: String?) {
    companion object {
        const val PREFIX = "lawallet:plugin"
        const val BAD_URL = "plugin_url"

        /**
         * Null when [text] isn't a plugin link at all. A `lawallet:plugin` link whose URL isn't an HTTPS GitHub source
         * comes back with `url = null`, so it's rejected instead of falling through to the payment parsers.
         */
        fun parse(text: String): PluginLink? {
            if (text.startsWith(PREFIX, ignoreCase = true)) {
                val query = text.substring(PREFIX.length).takeIf { it.startsWith("?") }?.drop(1) ?: return PluginLink(null)
                val raw = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return PluginLink(null)
                val url = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.trim() ?: return PluginLink(null)
                return PluginLink(url.takeIf(::isGitHubHttps))
            }
            // A bare folder URL (`…/tree/<ref>/…`). Plain repo links stay as they were: not a plugin payload.
            return if (isGitHubHttps(text) && "/tree/" in text) PluginLink(text) else null
        }

        /** What Add Plugin accepts as a GitHub source, and only over HTTPS. */
        private fun isGitHubHttps(url: String) = url.startsWith("https://", ignoreCase = true) && PluginSource.parse(url) is PluginSource.GitHub
    }
}

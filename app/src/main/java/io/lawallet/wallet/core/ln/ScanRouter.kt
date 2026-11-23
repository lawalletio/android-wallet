package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.btc.Bip21
import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.BtcInput
import io.lawallet.wallet.core.btc.SeedQr
import io.lawallet.wallet.core.btc.Unsupported
import io.lawallet.wallet.core.btc.Descriptors
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
    /**
     * Onchain wallet material for onchain import: an extended key (public or private), a descriptor, or a recovery
     * phrase (SeedQR, plain words). [text] is what import starts from; [label] names it ("zpub", "SeedQR").
     */
    data class WalletKey(val text: String, val label: String, val kind: KeyKind) : ScanResult
    enum class KeyKind { PUBLIC, PRIVATE, PHRASE }
    /** A Bitcoin address or `bitcoin:` request with no Lightning alternative. */
    data class Onchain(val request: Bip21) : ScanResult
    /** A plugin to offer through Add Plugin (resolver + permissions sheet). Never installed straight from a scan. */
    data class InstallPlugin(val url: String) : ScanResult
    /** A plugin link in a build without plugins (`DEBUG || PLUGINS` is false). */
    data object PluginsUnavailable : ScanResult
    data class Error(val code: String, val message: String) : ScanResult
}

/**
 * Routes what the scanner (camera, paste, deep links, NFC) read. Each format the app understands is one entry in
 * [formats], tried in order; the first that recognises the text decides. Order matters only where formats overlap:
 * specific before general (a plugin link before any URL, wallet keys and phrases before addresses, a unified `bitcoin:`
 * QR before plain Lightning), the network lookup (LNURL) next to last, and Lightning destinations last, which also
 * says why anything left over isn't supported. A new format is a new entry.
 *
 * [plugins]: whether this build has the Plugins section (`BuildConfig.DEBUG || BuildConfig.PLUGINS`).
 * [bip39]: the word list, for recovery phrases (none: phrases aren't recognised).
 */
class ScanRouter(private val lnurl: Lnurl, private val plugins: Boolean = false, private val bip39: (() -> Bip39)? = null) {
    private fun interface Format { suspend fun read(text: String, hosts: Set<String>): ScanResult? }

    private val formats: List<Format> = listOf(
        // lawallet:plugin?url=… or a GitHub plugin folder.
        Format { text, _ ->
            PluginLink.parse(text)?.let { link ->
                val url = link.url ?: return@Format ScanResult.Error(PluginLink.BAD_URL, "Not a GitHub plugin link")
                if (plugins) ScanResult.InstallPlugin(url) else ScanResult.PluginsUnavailable
            }
        },
        // A card activation link, this community's or another's.
        Format { text, hosts -> ActivationUrl.parse(text)?.let { a -> ScanResult.Activate(a.tokenId, hosts.any { it.equals(a.host, ignoreCase = true) }, text) } },
        // cashuA / cashuB tokens (animated UR ones arrive assembled).
        Format { text, _ -> CashuToken.parse(text)?.let { ScanResult.Cashu(it) } },
        // A recovery phrase: SeedQR (standard or compact) or the words themselves.
        Format { text, _ ->
            bip39?.invoke()?.let { SeedQr.read(text, it) }?.let { (words, label) -> ScanResult.WalletKey(words.joinToString(" "), label, ScanResult.KeyKind.PHRASE) }
        },
        // An extended key (xpub, zpub, xprv…) or an output descriptor.
        Format { text, _ ->
            BtcImport.walletKey(text)?.let { k ->
                val private = k is BtcInput.PrivateKey || (k as? BtcInput.Rejected)?.reason == Unsupported.PRIVATE_KEY
                ScanResult.WalletKey(text, BtcImport.keyLabel(text), if (private) ScanResult.KeyKind.PRIVATE else ScanResult.KeyKind.PUBLIC)
            }
        },
        // BIP-21: a unified QR pays over Lightning when it can; otherwise (or a bare address) onchain.
        Format { text, _ ->
            Bip21.parse(text)?.let { b ->
                b.lightning?.let { ln -> runCatching { DestinationParser.parse(ln) }.getOrNull()?.let { return@Format ScanResult.Pay(it) } }
                if (Descriptors.chainOf(b.address) != null) ScanResult.Onchain(b) else null
            }
        },
        // LNURL (bech32 or lnurlw/lnurlp schemes): resolved over the network to withdraw or pay.
        Format { text, _ ->
            if (!Lnurl.looksLikeLnurl(text)) null
            else try {
                when (val r = lnurl.resolve(text)) {
                    is LnurlResolved.Withdraw -> ScanResult.Withdraw(r.params)
                    is LnurlResolved.Pay -> ScanResult.Pay(Destination.LnurlPay(r.lnurlpUrl, null, null, null))
                    null -> ScanResult.Error("unsupported", "Unsupported code")
                }
            } catch (e: LnurlException) {
                ScanResult.Error(e.code ?: "lnurl", e.message ?: "LNURL error")
            }
        },
        // Lightning: an invoice, a Lightning address, an npub. Last: its error says why the rest isn't supported.
        Format { text, _ ->
            try { ScanResult.Pay(DestinationParser.parse(text)) } catch (e: DestinationException) { ScanResult.Error(e.code, e.message ?: "Unsupported code") }
        },
    )

    /** [instanceHosts]: hosts that count as "this community" (transport URL + advertised endpoint). */
    suspend fun route(raw: String, instanceHosts: Set<String>): ScanResult {
        val text = unwrapLightningParam(raw.trim())
        for (f in formats) f.read(text, instanceHosts)?.let { return it }
        return ScanResult.Error("unsupported", "Unsupported code")
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

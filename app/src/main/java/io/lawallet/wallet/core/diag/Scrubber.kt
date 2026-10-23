package io.lawallet.wallet.core.diag

import io.sentry.Breadcrumb
import io.sentry.SentryBaseEvent
import io.sentry.SentryEvent
import io.sentry.protocol.SentryTransaction

/**
 * What a crash report may carry (docs/monitoring-plan.md → "What to scrub"). Allowlists, not
 * denylists: keys, NWC strings, invoices, amounts and addresses must never leave the device, and
 * they hide in exception messages (kotlinx.serialization echoes its JSON input), thread names
 * (OkHttp names threads after URLs) and navigation routes (`activity/{paymentHash}`).
 */
object Scrubber {
    /** Breadcrumb origin of [Trail] lines: ours, already redacted, any category. */
    const val TRAIL = "trail"

    /** Last-line redaction for any free text that survives the allowlists. */
    private val patterns = listOf(
        Regex("""(?i)\b(nsec|npub|nprofile|nevent|naddr|note|lnurl|ln[a-z]{2,4}\d*[munp]?)1[02-9ac-hj-np-z]{6,}"""),
        Regex("""(?i)nostr\+?walletconnect:\S*"""),
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+"""), // URLs: LNURL callbacks carry k1 / pr=
        Regex("""(?i)\b[0-9a-f]{64}\b"""), // keys, payment hashes, preimages, event ids
        Regex("""(?i)secret=\S+"""),
        Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+"""), // JWT
        Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""), // Lightning addresses
        Regex("""(?i)\d[\d.,]*\s*(msats?|sats?|btc|₿)\b"""), // amounts
        Regex("""(?i)"?(amount|balance|fees?_paid)"?\s*[:=]\s*"?[\d.]+"""),
    )

    fun redact(s: String): String = patterns.fold(s) { acc, r -> r.replace(acc, "[redacted]") }

    /** Messages we write ourselves (Diagnostics); anything else is dropped. */
    private val ownMessages = listOf("exit.", "fgs.")
    /** The SDK's own breadcrumbs we keep; everything else from it (navigation, http, console) is dropped. */
    private val sdkCategories = setOf("app.lifecycle", "ui.lifecycle", "device.event", "network.event")
    private val tags = setOf(
        "reason", "importance", "pss_mb", "rss_mb", "state", "fgs_running", "relay_connected", "uptime_min",
        "heap_mb", "native_mb", "isSideLoaded", "installerStore",
    )
    private val contexts = setOf("app", "device", "os", "runtime", "trace", "gpu")

    fun event(e: SentryEvent): SentryEvent {
        base(e)
        e.exceptions?.forEach { ex ->
            ex.value = null
            ex.mechanism?.apply { description = null; data = null }
        }
        e.message = e.message?.takeIf { m -> ownMessages.any { m.formatted?.startsWith(it) == true } }
            ?.apply { formatted = formatted?.let(::redact); message = null; params = null }
        e.threads?.forEach { t -> t.name = t.name?.let { if (it.startsWith("OkHttp")) "OkHttp" else redact(it) } }
        e.transaction = e.transaction?.let(::redact)
        return e
    }

    /**
     * Performance traces: span descriptions can't be rewritten, so a span whose description
     * would need redacting is dropped, and a transaction whose name would is dropped whole.
     */
    fun transaction(t: SentryTransaction): SentryTransaction? {
        if (t.transaction?.let { redact(it) != it } == true) return null
        base(t)
        t.spans.removeAll { s -> s.description?.let { redact(it) != it } == true }
        t.spans.forEach { it.data = mutableMapOf() } // thread names (OkHttp: URLs) and the like
        return t
    }

    fun breadcrumb(b: Breadcrumb): Breadcrumb? {
        if (b.origin != TRAIL && b.category !in sdkCategories) return null
        b.message = b.message?.let(::redact)
        b.data.keys.toList().forEach { k -> (b.getData(k) as? String)?.let { b.setData(k, redact(it)) } }
        return b
    }

    private fun base(e: SentryBaseEvent) {
        e.user = null
        e.request = null
        e.serverName = null
        e.tags?.keys?.toList()?.forEach { k -> if (k in tags) e.setTag(k, redact(e.getTag(k).orEmpty())) else e.removeTag(k) }
        e.extras?.keys?.toList()?.forEach(e::removeExtra)
        e.contexts.keys().toList().forEach { if (it !in contexts) e.contexts.remove(it) }
        e.contexts.device?.apply { id = null; name = null } // the SDK's installation id
        e.breadcrumbs = e.breadcrumbs?.mapNotNull(::breadcrumb)
    }
}

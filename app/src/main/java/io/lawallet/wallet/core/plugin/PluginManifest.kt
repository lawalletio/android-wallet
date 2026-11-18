package io.lawallet.wallet.core.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** `manifest.json` of a plugin. Contract: docs/plugins.md. */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String,
    val api: Int = 1,
    val main: String = "index.js",
    val icon: String? = null,
    val provides: List<String> = emptyList(),
    val wallet: WalletSpec? = null,
    val background: Background? = null,
    val permissions: Permissions = Permissions(),
) {
    @Serializable data class WalletSpec(val methods: List<String> = emptyList())
    @Serializable data class Background(val pollSec: Int = 0)
    @Serializable data class Permissions(val hosts: List<String> = emptyList(), val secrets: Boolean = false, val webLogin: WebLogin? = null)
    @Serializable data class WebLogin(val url: String)

    val isWallet get() = "wallet" in provides && wallet != null

    /** Seconds between background polls; 0 = none. Floored so a plugin can't keep the radio awake. */
    val pollSec get() = background?.pollSec?.takeIf { it > 0 }?.coerceAtLeast(MIN_POLL_SEC) ?: 0

    companion object {
        const val API = 1
        const val MIN_POLL_SEC = 15
        private val ID = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$")
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates; null when the manifest isn't one this app can run. */
        fun parse(text: String): PluginManifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.valid() }
    }

    fun valid(): Boolean =
        ID.matches(id) && name.isNotBlank() && version.isNotBlank() && api == API &&
            relativePath(main) && (icon == null || relativePath(icon)) &&
            permissions.hosts.all { it.isNotBlank() && '/' !in it && ':' !in it } &&
            permissions.webLogin?.url?.toHttpUrlOrNull()?.isHttps != false
}

/** A path inside the plugin's own folder: no scheme, no absolute path, no `..`. */
internal fun relativePath(p: String) = p.isNotBlank() && !p.startsWith("/") && "://" !in p && p.split('/').none { it == ".." }

/**
 * Whether a plugin may call [url]: its host must be listed in the manifest, over HTTPS. [allowHttp] (debug builds)
 * also allows plain HTTP, so a plugin can be tried against a LAN server.
 */
fun hostAllowed(url: String, hosts: List<String>, allowHttp: Boolean = false): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    if (!u.isHttps && !allowHttp) return false
    return u.host.lowercase() in hosts.map { it.lowercase() }
}

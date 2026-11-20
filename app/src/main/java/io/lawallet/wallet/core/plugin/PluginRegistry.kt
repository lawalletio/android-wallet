package io.lawallet.wallet.core.plugin

import android.content.Context
import androidx.core.content.edit
import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class InstalledPlugin(
    val candidate: PluginCandidate,
    val sha256: String,
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
) {
    val id get() = candidate.manifest.id
    val manifest get() = candidate.manifest
}

/** The answer to a plugin's optional `account()`: signed in or not, and as whom (an email, a username). */
@Serializable
data class PluginAccount(val signedIn: Boolean = false, val label: String? = null)

/** A plugin's `host.prompt()`, answered by the dialog: the text, [PromptSpec.RESEND], or null (cancelled). */
class PromptRequest(val pluginId: String, val pluginName: String, val spec: PromptSpec, val result: CompletableDeferred<String?>)

/** A plugin asked the user to sign in on its declared origin; the PluginLogin screen answers [result]. */
class WebLoginRequest(val pluginName: String, val url: String, val extractJs: String, val result: CompletableDeferred<String?>)

/**
 * Installed plugins (docs/plugins.md). Device-local in their own prefs file, like OnchainStore: not tied to the
 * LaWallet account, so logout leaves them. Bundles live in `files/plugins/<id>/index.js` and are re-hashed against the
 * install record before they run.
 */
class PluginRegistry(context: Context, http: OkHttpClient, private val scope: CoroutineScope, private val allowHttp: Boolean) {
    private val sp = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "plugins")
    private val http = http
    private val json = io.lawallet.wallet.core.relaxedJson
    private val ser = ListSerializer(InstalledPlugin.serializer())
    val resolver = PluginResolver(http)

    private val _installed = MutableStateFlow(sp.getString("installed", null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty())
    val installed: StateFlow<List<InstalledPlugin>> = _installed


    private val _login = MutableStateFlow<WebLoginRequest?>(null)
    val loginRequest: StateFlow<WebLoginRequest?> = _login
    private val loginLock = Mutex()

    private val _prompt = MutableStateFlow<PromptRequest?>(null)
    /** A plugin waiting on the user (a security code…); AppNav shows it as a dialog over whatever is on screen. */
    val promptRequest: StateFlow<PromptRequest?> = _prompt

    val host = PluginHost(
        context, http, allowHttp,
        webLogin = { m, url, extract ->
            loginLock.withLock {
                val r = WebLoginRequest(m.name, url, extract, CompletableDeferred())
                _login.value = r
                try { r.result.await() } finally { _login.value = null }
            }
        },
        prompt = { m, spec ->
            loginLock.withLock {
                val r = PromptRequest(m.id, m.name, spec, CompletableDeferred())
                _prompt.value = r
                Trail.add("plugin", "${m.id} asks the user (${spec.input})")
                try { r.result.await() } finally { _prompt.value = null }
            }
        },
    )

    private val wallets = mutableMapOf<String, Pair<JsPlugin, JsWallet>>()

    private val _iconVersion = MutableStateFlow(0)
    /** Bumps when an icon lands on the device, so whatever shows it picks it up. */
    val iconVersion: StateFlow<Int> = _iconVersion

    init {
        // The install's icon fetch is best effort (offline, a timeout): retry the missing ones, or their cards keep a puzzle piece.
        scope.launch(Dispatchers.IO) { _installed.value.filter { icon(it.id) == null }.forEach { fetchIcon(it.candidate) } }
    }

    private fun save(list: List<InstalledPlugin>) {
        _installed.value = list
        sp.edit { putString("installed", json.encodeToString(ser, list)) }
    }

    fun get(id: String) = _installed.value.find { it.id == id }

    /**
     * Downloads [c]'s bundle and installs it. An update from the same source keeps what the plugin stored (its login);
     * the same id from a *different* source starts clean, so a look-alike plugin can never read another's session.
     */
    suspend fun install(c: PluginCandidate): InstalledPlugin = withContext(Dispatchers.IO) {
        val url = c.mainUrl.toHttpUrlOrNull() ?: throw PluginSourceException("Bad bundle URL")
        if (!url.isHttps && !allowHttp) throw PluginSourceException("Plugins must be served over HTTPS")
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw PluginSourceException("HTTP ${r.code}")
            r.body.bytes().also { if (it.size > MAX_BUNDLE) throw PluginSourceException("Plugin is too large") }
        }
        val hash = sha256(bytes).toHex()
        get(c.manifest.id)?.takeIf { it.candidate.source != c.source }?.let { other ->
            unload(other.id)
            host.clear(other.id)
            forgetAccount(other.id)
            File(dir, other.id).resolve("icon").delete() // the other plugin's: never shown under this one
            Trail.add("plugin", "${other.id} installed from another source: its data was wiped")
        }
        val folder = File(dir, c.manifest.id).apply { mkdirs() }
        folder.resolve("index.js").writeBytes(bytes)
        if (c.iconUrl == null) folder.resolve("icon").delete() else fetchIcon(c)
        unload(c.manifest.id)
        val p = InstalledPlugin(c, hash, enabled = get(c.manifest.id)?.enabled ?: true)
        save(_installed.value.filter { it.id != p.id } + p)
        Trail.add("plugin", "installed ${p.id} ${p.manifest.version}")
        p
    }

    /** Uninstalls [id] and wipes everything it stored (session, tokens, caches). */
    fun remove(id: String) {
        unload(id)
        forgetAccount(id)
        File(dir, id).deleteRecursively()
        host.clear(id)
        save(_installed.value.filter { it.id != id })
        Trail.add("plugin", "removed $id")
    }

    /** The plugin's icon on this device, if it has one. */
    fun icon(id: String): File? = File(dir, "$id/icon").takeIf { it.isFile }

    /**
     * Keeps [c]'s icon on the device (the add-wallet choices and wallet cards show it offline). Best effort: a failed
     * fetch keeps the icon it has. Only into an installed plugin's folder, so a removal racing it leaves nothing behind.
     */
    private fun fetchIcon(c: PluginCandidate) {
        val url = c.iconUrl?.toHttpUrlOrNull()?.takeIf { it.isHttps || allowHttp } ?: return
        val folder = File(dir, c.manifest.id).takeIf { it.isDirectory } ?: return
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes().takeIf { it.size <= MAX_ICON }?.let { folder.resolve("icon").writeBytes(it); _iconVersion.update { v -> v + 1 } }
            }
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        if (!on) unload(id)
        save(_installed.value.map { if (it.id == id) it.copy(enabled = on) else it })
    }


    /**
     * The wallet of an enabled wallet plugin, the same instance every time (until it's updated or stopped). The bundle
     * (up to 2 MB, hashed) is read off the main thread: Home's deck and the Plugins screen call this from composition scopes.
     */
    suspend fun wallet(id: String): JsWallet? {
        synchronized(this) { wallets[id]?.takeIf { !it.first.dead }?.let { return it.second } }
        val p = get(id)?.takeIf { it.enabled && it.manifest.isWallet } ?: run { unload(id); return null }
        val code = withContext(Dispatchers.IO) { load(p) } ?: return null
        synchronized(this) {
            wallets[id]?.takeIf { !it.first.dead }?.let { return it.second } // another caller loaded it meanwhile
            unload(id)
            val plugin = JsPlugin(p.manifest, code, host, scope)
            return JsWallet(plugin) { signedOut(id) }.also { wallets[id] = plugin to it }
        }
    }

    /** Runs the plugin's `login()` (which may open the web login). True when it finished without error. */
    suspend fun login(id: String): Boolean = runCatching { call(id, "login", LOGIN_TIMEOUT_MS) }.fold(
        { Trail.add("plugin", "$id login ok"); setAccounts(_accounts.value - id); refreshAccount(id); true },
        { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Trail.add("plugin", "$id login failed ${(e as? io.lawallet.wallet.core.nwc.NwcException)?.code ?: e.javaClass.simpleName}", error = true)
            refreshAccount(id)
            false
        },
    )

    /**
     * Runs the plugin's `logout()`; a plugin without one has everything it stored wiped instead (its session included).
     * True when it's signed out.
     */
    suspend fun logout(id: String): Boolean {
        val ok = runCatching { call(id, "logout", 20_000) }.fold({ true }, { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            if ((e as? io.lawallet.wallet.core.nwc.NwcException)?.code == "NOT_IMPLEMENTED") { unload(id); host.clear(id); true } else false
        })
        Trail.add("plugin", "$id logout ${if (ok) "ok" else "failed"}", error = !ok)
        if (ok) signedOut(id) // a plugin without `account()` would otherwise go back to "unknown", listed as a wallet
        refreshAccount(id)
        return ok
    }

    private val noAccount: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() // bundle hashes

    private val accountsSer = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), PluginAccount.serializer())
    // Kept on disk, so Home knows which plugin wallets are signed in before any plugin runs.
    private val _accounts = MutableStateFlow(sp.getString("accounts", null)?.let { runCatching { json.decodeFromString(accountsSer, it) }.getOrNull() }.orEmpty())
    /** Who each plugin is signed in as, from its optional `account()`; missing = unknown or not supported. */
    val accounts: StateFlow<Map<String, PluginAccount>> = _accounts

    /** Asks [id] who it's signed in as (Plugins screen, after a login). A plugin without `account()` shows nothing. */
    suspend fun refreshAccount(id: String) {
        val sha = get(id)?.sha256
        // A bundle without `account()` is asked once per process: each ask may start a JS engine just to learn that.
        val a = if (sha in noAccount) null else runCatching { json.decodeFromString(PluginAccount.serializer(), call(id, "account", 10_000)) }
            .onFailure { if ((it as? io.lawallet.wallet.core.nwc.NwcException)?.code == "NOT_IMPLEMENTED" && sha != null) noAccount += sha }
            .getOrNull()
        // No answer keeps a known sign-out (only a login clears it); otherwise unknown.
        val next = if (a == null) _accounts.value.takeIf { it[id]?.signedIn == false } ?: (_accounts.value - id) else _accounts.value + (id to a)
        setAccounts(next)
    }

    /**
     * [id] answered UNAUTHORIZED (or signed out): signed out until a login, so Home drops its card and stops polling it.
     * The field log had a signed-out plugin's balance read every 10 s, thousands of times.
     */
    fun signedOut(id: String) {
        if (_accounts.value[id]?.signedIn == false) return
        Trail.add("plugin", "$id signed out")
        setAccounts(_accounts.value + (id to PluginAccount(signedIn = false)))
    }

    private fun forgetAccount(id: String) = setAccounts(_accounts.value - id)

    private fun setAccounts(next: Map<String, PluginAccount>) {
        if (next == _accounts.value) return
        _accounts.value = next
        sp.edit { putString("accounts", json.encodeToString(accountsSer, next)) }
    }

    /** One call on [id]'s JS: its loaded instance if it has one, else a throwaway one. */
    private suspend fun call(id: String, fn: String, timeoutMs: Long): String {
        val p = get(id) ?: throw PluginSourceException("Not installed")
        val plugin = wallets[id]?.first?.takeIf { !it.dead }
            ?: JsPlugin(p.manifest, withContext(Dispatchers.IO) { load(p) } ?: throw PluginSourceException("Bundle changed"), host, scope)
        return try { plugin.call(fn, JsonArray(emptyList()), timeoutMs) } finally { if (wallets[id]?.first !== plugin) plugin.close() }
    }

    @Synchronized
    private fun unload(id: String) { wallets.remove(id)?.first?.close() }

    /** The bundle, only when it still matches the hash recorded at install. */
    private fun load(p: InstalledPlugin): String? {
        val bytes = runCatching { File(dir, p.id).resolve("index.js").readBytes() }.getOrNull() ?: return null
        if (sha256(bytes).toHex() != p.sha256) {
            Trail.add("plugin", "${p.id} bundle hash mismatch, not loading", error = true)
            return null
        }
        return String(bytes)
    }

    private companion object {
        const val MAX_BUNDLE = 2 * 1024 * 1024
        const val MAX_ICON = 512 * 1024
        // The user types a password and maybe an emailed code in the web login.
        const val LOGIN_TIMEOUT_MS = 15 * 60_000L
    }
}

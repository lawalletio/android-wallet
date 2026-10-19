package io.lawallet.wallet.app

import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.InstanceSettings
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.auth.AmberSigner
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.LocalSigner
import io.lawallet.wallet.core.nostr.Signer
import io.lawallet.wallet.core.store.Prefs
import io.lawallet.wallet.core.store.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

enum class AuthState { Loading, LoggedOut, LoggedIn }

/** Instance + identity + API session. One instance at a time; switching keeps the key. */
class Session(
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val amber: AmberSigner.Factory,
) {
    private val _signer = MutableStateFlow<Signer?>(loadSigner())
    val signer: StateFlow<Signer?> = _signer

    private val _api = MutableStateFlow(buildApi(prefs.instanceUrl))
    val api: StateFlow<LaWalletApi> = _api

    private val _settings = MutableStateFlow(prefs.settingsCache?.let { runCatching { LaWalletApi.json.decodeFromString(InstanceSettings.serializer(), it) }.getOrNull() })
    val settings: StateFlow<InstanceSettings?> = _settings

    private val _me = MutableStateFlow<CurrentUser?>(null)
    val me: StateFlow<CurrentUser?> = _me

    private val _auth = MutableStateFlow(if (_signer.value == null) AuthState.LoggedOut else AuthState.Loading)
    val auth: StateFlow<AuthState> = _auth

    val loginMethod get() = prefs.loginMethod
    val instanceUrl get() = prefs.instanceUrl
    val instanceHost: String get() = prefs.instanceUrl.toHttpUrlOrNull()?.let { if (it.port == okhttp3.HttpUrl.defaultPort(it.scheme)) it.host else "${it.host}:${it.port}" } ?: ""

    /** Local secret (nsec/passkey logins) — what Security → backup reveals. */
    fun secretHex(): String? = (_signer.value as? LocalSigner)?.keys?.secretHex

    init {
        scope.launch { refreshSettings() }
        if (_signer.value != null) scope.launch {
            // Restore: a stored JWT is enough to show the wallet; me() re-mints when it expired.
            runCatching { refreshMe() }
                .onSuccess { _auth.value = AuthState.LoggedIn }
                .onFailure { _auth.value = if (it is ApiException && it.status in 401..403) AuthState.LoggedOut else AuthState.LoggedIn }
        }
    }

    private fun loadSigner(): Signer? = when (prefs.loginMethod) {
        "amber" -> prefs.externalSigner?.let { (pk, pkg) -> amber.create(pk, pkg) }
        null -> null
        else -> prefs.signerSecretBlob?.let { runCatching { LocalSigner(Keys.fromHex(SecretStore.decrypt(it))) }.getOrNull() }
    }

    private fun buildApi(url: String) = LaWalletApi(url, http, { _signer.value }, object : LaWalletApi.TokenStore {
        override fun load() = prefs.jwt
        override fun save(token: String?) { prefs.jwt = token }
    }, { _settings.value?.endpoint?.takeIf { it.isNotBlank() } })

    suspend fun refreshSettings(): InstanceSettings? = runCatching { _api.value.settings() }.getOrNull()?.also {
        _settings.value = it
        prefs.settingsCache = LaWalletApi.json.encodeToString(InstanceSettings.serializer(), it)
    }

    /** Re-reads /api/users/me — also creates the account + courtesy wallet on first call. */
    suspend fun refreshMe(): CurrentUser = _api.value.me().also { _me.value = it }

    /** nsec / passkey login. Mints the JWT and materializes the account before returning. */
    suspend fun loginWithSecret(secretHex: String, method: String) {
        val keys = Keys.fromHex(secretHex)
        login(LocalSigner(keys), method) {
            prefs.signerSecretBlob = SecretStore.encrypt(keys.secretHex)
        }
    }

    suspend fun loginWithAmber(pubkey: String, pkg: String) = login(amber.create(pubkey, pkg), "amber") {
        prefs.externalSigner = pubkey to pkg
    }

    private suspend fun login(signer: Signer, method: String, persist: () -> Unit) {
        val previous = _signer.value
        _signer.value = signer
        prefs.jwt = null
        try {
            _api.value.login()
            refreshMe()
        } catch (e: Exception) {
            _signer.value = previous
            prefs.jwt = null
            throw e
        }
        persist()
        prefs.loginMethod = method
        _auth.value = AuthState.LoggedIn
    }

    fun logout() {
        prefs.clearSession()
        _signer.value = null
        _me.value = null
        _api.value = buildApi(prefs.instanceUrl)
        _auth.value = AuthState.LoggedOut
    }

    /**
     * Validates [rawUrl] as a LaWallet instance (public settings + version), then switches to it.
     * Requests keep going to [rawUrl]; NIP-98 signs for the instance's advertised `endpoint`.
     */
    suspend fun switchInstance(rawUrl: String): String {
        val candidate = normalizeUrl(rawUrl) ?: throw IllegalArgumentException("Enter a valid URL")
        val probe = buildApi(candidate)
        val settings = probe.settings()
        probe.version()
        val url = candidate
        prefs.instanceUrl = url
        prefs.clearInstanceCaches()
        _settings.value = settings
        _api.value = buildApi(url)
        prefs.settingsCache = LaWalletApi.json.encodeToString(InstanceSettings.serializer(), settings)
        _me.value = null
        if (_signer.value != null) {
            _auth.value = AuthState.Loading
            runCatching { _api.value.login(); refreshMe() }
                .onSuccess { _auth.value = AuthState.LoggedIn }
                .onFailure { _auth.value = AuthState.LoggedIn }
        }
        return url
    }

    companion object {
        fun normalizeUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
            val u = withScheme.toHttpUrlOrNull() ?: return null
            return "${u.scheme}://${u.host}" + if (u.port == okhttp3.HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
        }

    }
}

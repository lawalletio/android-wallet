package io.lawallet.wallet.core.api

import io.lawallet.wallet.core.nostr.Signer
import io.lawallet.wallet.core.nostr.nip98Header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64

class ApiException(val status: Int, message: String, val code: String? = null) : Exception(message)

/**
 * LaWallet REST client for one instance. Auth = NIP-98-signed `POST /api/jwt` → Bearer.
 * There is no refresh endpoint: the session is re-minted ~5 min before `exp`, and once on a 401.
 */
class LaWalletApi(
    baseUrl: String,
    private val http: OkHttpClient,
    private val signerProvider: () -> Signer?,
    private val tokenStore: TokenStore,
    /** Origin the server accepts in the NIP-98 `u` tag (its configured `endpoint`); defaults to [baseUrl]. */
    private val authOrigin: () -> String? = { null },
) {
    interface TokenStore {
        fun load(): String?
        fun save(token: String?)
    }

    val baseUrl: String = baseUrl.trimEnd('/')
    private val mintLock = Mutex()
    private val jsonType = "application/json".toMediaType()

    // ---------- public ----------
    suspend fun settings() = get("/api/settings", InstanceSettings.serializer(), auth = false)
    suspend fun version() = get("/api/version", VersionInfo.serializer(), auth = false)
    suspend fun checkUsername(username: String) =
        get("/api/lightning-addresses/check?username=${enc(username)}", UsernameAvailability.serializer(), auth = false)
    suspend fun activationPreview(id: String) = get("/api/activation-tokens/${enc(id)}", ActivationPreview.serializer(), auth = false)
    suspend fun passkeyRegistrationOptions(label: String?) =
        send("POST", "/api/auth/passkey/registration/options", buildJsonObject { if (label != null) put("label", label) }, PasskeyOptions.serializer(), auth = false)

    // ---------- session ----------
    /** Mints a fresh 24h JWT with the current signer. */
    suspend fun login(): String = mintLock.withLock { mint() }

    fun hasSession() = tokenStore.load() != null
    fun logout() = tokenStore.save(null)

    private suspend fun mint(): String {
        val signer = signerProvider() ?: throw ApiException(401, "Not signed in")
        val url = "$baseUrl/api/jwt"
        val body = """{"expiresIn":"24h"}""".toByteArray()
        val auth = signer.nip98Header((authOrigin()?.trimEnd('/') ?: baseUrl) + "/api/jwt", "POST", body)
        val res = execute(Request.Builder().url(url).header("Authorization", auth).post(body.toRequestBody(jsonType)).build())
        val token = json.decodeFromString(JwtResponse.serializer(), res).token
        tokenStore.save(token)
        return token
    }

    private suspend fun token(): String = mintLock.withLock {
        val current = tokenStore.load()
        if (current != null && !expiresSoon(current)) current else mint()
    }

    // ---------- account ----------
    /** Also materializes the user + courtesy wallet on first call, and revives dead LNCurl wallets. */
    suspend fun me() = get("/api/users/me", CurrentUser.serializer())
    suspend fun setCurrencyPrefs(prefs: CurrencyPrefs) =
        send("PUT", "/api/users/me/currency-prefs", buildJsonObject {
            putJsonObject("currencyPrefs") {
                putJsonArray("active") { prefs.active.forEach { add(JsonPrimitive(it)) } }
                put("selected", prefs.selected)
            }
        }, JsonElement.serializer())
    suspend fun jwtInfo() = get("/api/jwt", JwtInfo.serializer())
    /**
     * Permanently deletes the account (addresses released, cards unpaired). Returns false on 404/405: the
     * instance predates the endpoint, so deletion goes through the email process in PRIVACY.md.
     */
    suspend fun deleteAccount(): Boolean = try {
        send("DELETE", "/api/account", buildJsonObject { put("confirm", "DELETE") }, JsonElement.serializer())
        true
    } catch (e: ApiException) {
        if (e.status == 404 || e.status == 405) false else throw e
    }
    suspend fun profiles(pubkeys: List<String>) =
        send("POST", "/api/nostr/profiles", buildJsonObject { putJsonArray("pubkeys") { pubkeys.forEach { add(JsonPrimitive(it)) } } }, NostrProfiles.serializer()).profiles

    // ---------- addresses ----------
    suspend fun addresses() = get("/api/wallet/addresses", ListSerializer(WalletAddress.serializer()))
    /** Throws ApiException(402) when the instance charges for addresses. */
    suspend fun createAddress(username: String) =
        send("POST", "/api/wallet/addresses", buildJsonObject { put("username", username) }, WalletAddress.serializer())
    suspend fun setPrimaryAddress(username: String) = send("POST", "/api/wallet/addresses/${enc(username)}/primary", null, JsonElement.serializer())
    suspend fun createAddressInvoice(username: String) =
        send("POST", "/api/invoices", buildJsonObject {
            put("purpose", "wallet-address")
            putJsonObject("metadata") { put("username", username) }
        }, RegistrationInvoice.serializer())
    suspend fun claimInvoice(id: String, preimage: String) =
        send("POST", "/api/invoices/${enc(id)}/claim", buildJsonObject { put("preimage", preimage) }, ClaimInvoiceResult.serializer())

    // ---------- remote wallets ----------
    suspend fun remoteWallets() = get("/api/remote-wallets", ListSerializer(RemoteWallet.serializer()))
    suspend fun remoteWallet(id: String) = get("/api/remote-wallets/${enc(id)}", RemoteWallet.serializer())
    suspend fun createNwcWallet(name: String, connectionString: String, mode: String, isDefault: Boolean) =
        send("POST", "/api/remote-wallets", buildJsonObject {
            put("name", name)
            put("type", "NWC")
            putJsonObject("config") { put("connectionString", connectionString); put("mode", mode) }
            put("isDefault", isDefault)
        }, RemoteWallet.serializer())
    suspend fun createLncurlWallet(name: String? = null, isDefault: Boolean? = null) =
        send("POST", "/api/remote-wallets/lncurl", buildJsonObject {
            if (name != null) put("name", name)
            if (isDefault != null) put("isDefault", isDefault)
        }, RemoteWallet.serializer())
    suspend fun updateRemoteWallet(id: String, name: String? = null, isDefault: Boolean? = null, status: String? = null) =
        send("PATCH", "/api/remote-wallets/${enc(id)}", buildJsonObject {
            if (name != null) put("name", name)
            if (isDefault != null) put("isDefault", isDefault)
            if (status != null) put("status", status)
        }, RemoteWallet.serializer())
    suspend fun deleteRemoteWallet(id: String, permanent: Boolean = false) =
        send("DELETE", "/api/remote-wallets/${enc(id)}" + if (permanent) "?permanent=true" else "", null, JsonElement.serializer())
    suspend fun remoteWalletConnectionString(id: String) = get("/api/remote-wallets/${enc(id)}/connection-string", ConnectionString.serializer()).connectionString
    suspend fun remoteWalletBalance(id: String) = get("/api/remote-wallets/${enc(id)}/balance", WalletBalance.serializer()).balanceSats
    suspend fun remoteWalletNotifications(id: String) =
        get("/api/remote-wallets/${enc(id)}/notifications", JsonElement.serializer()).let { el ->
            val arr = (el as? JsonObject)?.get("notifications") ?: el
            json.decodeFromJsonElement(ListSerializer(WalletNotification.serializer()), arr)
        }
    suspend fun createWebhookNotification(id: String, name: String, action: String, webhookUrl: String) =
        send("POST", "/api/remote-wallets/${enc(id)}/notifications", buildJsonObject {
            put("name", name); put("channel", "WEBHOOK"); put("action", action); put("webhookUrl", webhookUrl)
        }, JsonElement.serializer())
    suspend fun setNotificationEnabled(id: String, notificationId: String, enabled: Boolean) =
        send("PATCH", "/api/remote-wallets/${enc(id)}/notifications/${enc(notificationId)}", buildJsonObject { put("enabled", enabled) }, JsonElement.serializer())

    suspend fun receiveAction(id: String) = get("/api/remote-wallets/${enc(id)}/receive-action", ReceiveAction.serializer())
    suspend fun saveReceiveAction(id: String, feeBps: Int, baseFeeSats: Long, destinations: List<ForwardDestination>) =
        send("PUT", "/api/remote-wallets/${enc(id)}/receive-action", buildJsonObject {
            put("feeBps", feeBps); put("baseFeeSats", baseFeeSats); put("enabled", true)
            putJsonArray("destinations") { destinations.forEach { d -> add(buildJsonObject { put("address", d.address); put("allocationBps", d.allocationBps) }) } }
        }, JsonElement.serializer())
    suspend fun toggleReceiveAction(id: String, enabled: Boolean) =
        send("PATCH", "/api/remote-wallets/${enc(id)}/receive-action", buildJsonObject { put("enabled", enabled) }, JsonElement.serializer())

    // ---------- cards ----------
    suspend fun cards() = get("/api/wallet/cards", ListSerializer(WalletCard.serializer()))
    suspend fun setCardEnabled(id: String, enabled: Boolean) =
        send("PATCH", "/api/wallet/cards/${enc(id)}", buildJsonObject { put("enabled", enabled) }, WalletCard.serializer())
    suspend fun linkCardToDefaultWallet(id: String) =
        send("PATCH", "/api/wallet/cards/${enc(id)}", buildJsonObject { put("linkDefaultWallet", true) }, WalletCard.serializer())
    suspend fun claimActivation(id: String) =
        send("POST", "/api/activation-tokens/${enc(id)}/claim", buildJsonObject { put("remoteWalletId", JsonNull) }, ActivationClaim.serializer())

    // ---------- passkeys / oauth ----------
    suspend fun passkeyRegistrationVerify(body: JsonObject, authenticated: Boolean) =
        send("POST", "/api/auth/passkey/registration/verify", body, JsonElement.serializer(), auth = authenticated)
    suspend fun passkeys() = get("/api/auth/passkey/credentials", PasskeyCredentials.serializer()).credentials
    suspend fun renamePasskey(id: String, label: String) =
        send("PATCH", "/api/auth/passkey/credentials/${enc(id)}", buildJsonObject { put("label", label) }, JsonElement.serializer())
    suspend fun deletePasskey(id: String) = send("DELETE", "/api/auth/passkey/credentials/${enc(id)}", null, JsonElement.serializer())
    suspend fun oauthGrants() = get("/api/oauth/grants", OAuthGrants.serializer()).grants
    suspend fun revokeGrant(id: String) = send("DELETE", "/api/oauth/grants/${enc(id)}", null, JsonElement.serializer())

    // ---------- plumbing ----------
    private suspend fun <T> get(path: String, ser: KSerializer<T>, auth: Boolean = true): T = send("GET", path, null, ser, auth)

    private suspend fun <T> send(method: String, path: String, body: JsonObject?, ser: KSerializer<T>, auth: Boolean = true): T {
        val bytes = body?.toString()?.toByteArray()
        fun build(token: String?): Request {
            val b = Request.Builder().url(baseUrl + path).header("accept", "application/json")
            if (token != null) b.header("Authorization", "Bearer $token")
            return b.method(method, bytes?.toRequestBody(jsonType) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(jsonType)).build()
        }
        val text = if (!auth) execute(build(null)) else try {
            execute(build(token()))
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            execute(build(mintLock.withLock { mint() }))
        }
        val el = if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        return json.decodeFromJsonElement(ser, el)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) {
                val err = runCatching { json.parseToJsonElement(text).jsonObject["error"] }.getOrNull()
                val message = when (err) {
                    is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> err.contentOrNull
                    else -> null
                } ?: "Request failed (${res.code})"
                val code = (err as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                android.util.Log.w("LaWalletApi", "${request.method} ${request.url.encodedPath} -> ${res.code} $message")
                throw ApiException(res.code, message, code)
            }
            text
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** True when the JWT's `exp` is < 5 min away (jwt-expiry.ts). Unparseable tokens count as expired. */
        fun expiresSoon(token: String, nowMs: Long = System.currentTimeMillis()): Boolean = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1].padEnd((token.split('.')[1].length + 3) / 4 * 4, '=')))
            val exp = json.parseToJsonElement(payload).jsonObject["exp"]!!.jsonPrimitive.content.toLong()
            exp * 1000 - nowMs < 5 * 60_000
        }.getOrDefault(true)
    }
}

package io.lawallet.wallet.core.store

import android.content.Context
import androidx.core.content.edit
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.core.fx.Currencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable data class CurrencyState(val active: List<String> = Currencies.DEFAULT_ACTIVE, val selected: String = Currencies.DEFAULT_SELECTED, val localEdit: Boolean = false)
@Serializable data class Contact(val address: String, val name: String? = null, val picture: String? = null, val savedAt: Long = 0)

/** Small typed wrapper over SharedPreferences — the Android twin of the web wallet's localStorage keys. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lawallet", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var instanceUrl: String
        get() = sp.getString("instance", null) ?: BuildConfig.DEFAULT_INSTANCE
        set(v) = sp.edit { putString("instance", v) }

    var loginMethod: String?
        get() = sp.getString("login-method", null)
        set(v) = sp.edit { putString("login-method", v) }

    /** Keystore-encrypted signer secret (hex). */
    var signerSecretBlob: String?
        get() = sp.getString("signer-secret", null)
        set(v) = sp.edit { putString("signer-secret", v) }

    /** NIP-55 external signer (Amber): pubkey + package. */
    var externalSigner: Pair<String, String>?
        get() = sp.getString("ext-signer-pubkey", null)?.let { it to (sp.getString("ext-signer-pkg", null) ?: "") }
        set(v) = sp.edit { putString("ext-signer-pubkey", v?.first); putString("ext-signer-pkg", v?.second) }

    var jwt: String?
        get() = sp.getString("jwt", null)
        set(v) = sp.edit { putString("jwt", v) }

    var settingsCache: String?
        get() = sp.getString("settings:$instanceUrl", null)
        set(v) = sp.edit { putString("settings:$instanceUrl", v) }

    private val _currencies = MutableStateFlow(
        sp.getString("currencies", null)?.let { runCatching { json.decodeFromString(CurrencyState.serializer(), it) }.getOrNull() } ?: CurrencyState(),
    )
    val currencies: StateFlow<CurrencyState> = _currencies
    fun setCurrencies(state: CurrencyState) {
        _currencies.value = state
        sp.edit { putString("currencies", json.encodeToString(CurrencyState.serializer(), state)) }
    }

    var ratesJson: String?
        get() = sp.getString("rates", null)
        set(v) = sp.edit { putString("rates", v) }

    private val _sound = MutableStateFlow(sp.getString("payment-sound", "off") ?: "off")
    val paymentSound: StateFlow<String> = _sound
    fun setPaymentSound(v: String) { _sound.value = v; sp.edit { putString("payment-sound", v) } }

    private val _hideBalance = MutableStateFlow(sp.getBoolean("hide-balance", false))
    val hideBalance: StateFlow<Boolean> = _hideBalance
    fun setHideBalance(v: Boolean) { _hideBalance.value = v; sp.edit { putBoolean("hide-balance", v) } }

    private val _bgListener = MutableStateFlow(sp.getBoolean("bg-listener", true))
    val backgroundListener: StateFlow<Boolean> = _bgListener
    fun setBackgroundListener(v: Boolean) { _bgListener.value = v; sp.edit { putBoolean("bg-listener", v) } }

    var contacts: List<Contact>
        get() = sp.getString("contacts", null)?.let { runCatching { json.decodeFromString(ListSerializer(Contact.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = sp.edit { putString("contacts", json.encodeToString(ListSerializer(Contact.serializer()), v.take(12))) }

    fun cachedBalance(nwcKey: String): Long? = sp.getLong("balance:$nwcKey", -1).takeIf { it >= 0 }
    fun setCachedBalance(nwcKey: String, sats: Long) = sp.edit { putLong("balance:$nwcKey", sats) }

    fun activityCache(nwcKey: String): String? = sp.getString("activity:$nwcKey", null)
    fun setActivityCache(nwcKey: String, v: String) = sp.edit { putString("activity:$nwcKey", v) }

    /** Last settled payment we've notified about (seconds), so a reconnect can catch up via list_transactions. */
    fun lastSeen(nwcKey: String): Long = sp.getLong("last-seen:$nwcKey", 0)
    fun setLastSeen(nwcKey: String, s: Long) = sp.edit { putLong("last-seen:$nwcKey", s) }

    fun seen(nwcKey: String): List<String> = sp.getString("seen:$nwcKey", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    fun setSeen(nwcKey: String, keys: List<String>) = sp.edit { putString("seen:$nwcKey", keys.takeLast(200).joinToString("\n")) }

    var pendingActivation: Pair<String, Long>?
        get() = sp.getString("pending-activation", null)?.let { it to sp.getLong("pending-activation-at", 0) }
        set(v) = sp.edit { putString("pending-activation", v?.first); putLong("pending-activation-at", v?.second ?: 0) }

    var bonusSats: Long
        get() = sp.getLong("bonus-sats", 0)
        set(v) = sp.edit { putLong("bonus-sats", v) }

    var avatarCache: Map<String, String>
        get() = sp.getString("profiles", null)?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() } ?: emptyMap()
        set(v) = sp.edit { putString("profiles", json.encodeToString(MapSerializer(String.serializer(), String.serializer()), v)) }

    /** Logout: wipes everything except the instance and display preferences (session-cache.ts). */
    fun clearSession() {
        val keep = setOf("instance", "payment-sound", "currencies", "bg-listener")
        sp.edit { sp.all.keys.filter { it !in keep }.forEach { remove(it) } }
    }

    /** Instance switch: the session and every per-instance cache go; the key stays. */
    fun clearInstanceCaches() {
        val keep = setOf("instance", "payment-sound", "currencies", "bg-listener", "login-method", "signer-secret", "ext-signer-pubkey", "ext-signer-pkg")
        sp.edit { sp.all.keys.filter { it !in keep && !it.startsWith("settings:") }.forEach { remove(it) } }
    }
}

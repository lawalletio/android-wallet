package io.lawallet.wallet.app

import android.content.Context
import androidx.core.content.edit
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.core.store.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A self-custody onchain wallet kept on this device. [watch] is set for watch-only wallets (no secret);
 * seed wallets keep their phrase Keystore-wrapped in [OnchainStore], never in this record.
 */
@Serializable
data class OnchainWallet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val script: ScriptType? = null,
    /** Watch-only: what was imported (xpub/zpub, descriptor or address); null for seed wallets. */
    val watch: WatchKind? = null,
    val source: String? = null,
    val words: Int = 0,
    val passphrase: Boolean = false,
    /** Seed wallets created here start false until the phrase is verified. */
    val backedUp: Boolean = true,
    val isPrimary: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val watchOnly get() = watch != null
    val path get() = if (watch == WatchKind.ADDRESS) null else script?.path
}

/**
 * Device-local, in its own prefs file: these wallets aren't tied to the LaWallet account, so logging out
 * (Prefs.clearSession) must never wipe a recovery phrase. There's always one primary while any exist.
 */
class OnchainStore(context: Context) {
    private val sp = context.getSharedPreferences("onchain", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(OnchainWallet.serializer())
    private val _wallets = MutableStateFlow(sp.getString("wallets", null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty())
    val wallets: StateFlow<List<OnchainWallet>> = _wallets

    private fun save(list: List<OnchainWallet>) {
        val fixed = if (list.isNotEmpty() && list.none { it.isPrimary }) list.mapIndexed { i, w -> w.copy(isPrimary = i == 0) } else list
        _wallets.value = fixed
        sp.edit { putString("wallets", json.encodeToString(ser, fixed)) }
    }

    /** [secret]: the phrase (and BIP39 passphrase on a second line). Blocking (Keystore): call off the main thread. */
    fun add(w: OnchainWallet, secret: String? = null): OnchainWallet {
        if (secret != null) sp.edit { putString("secret:${w.id}", SecretStore.encrypt(secret)) }
        val added = w.copy(isPrimary = w.isPrimary || _wallets.value.isEmpty())
        save(_wallets.value.map { if (added.isPrimary) it.copy(isPrimary = false) else it } + added)
        return added
    }

    fun update(id: String, f: (OnchainWallet) -> OnchainWallet) = save(_wallets.value.map { if (it.id == id) f(it) else it })
    fun setPrimary(id: String) = save(_wallets.value.map { it.copy(isPrimary = it.id == id) })
    fun remove(id: String) {
        sp.edit { remove("secret:$id") }
        save(_wallets.value.filter { it.id != id })
    }

    /** Phrase words (and passphrase), or null. Blocking (Keystore). */
    fun secret(id: String): Pair<List<String>, String?>? = sp.getString("secret:$id", null)?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }
        ?.split('\n')?.let { it[0].split(' ') to it.getOrNull(1)?.takeIf(String::isNotEmpty) }
}

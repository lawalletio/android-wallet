package io.lawallet.wallet.app

import android.content.Context
import androidx.core.content.edit
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.DescriptorPair
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.core.store.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
    val chain: Chain = Chain.MAINNET,
    /** Public descriptors BDK derives from; null for a single-address watch, or until first derived (older records). */
    val descriptors: DescriptorPair? = null,
    /** Multisig ([watch] = MULTISIG): the seed wallet in this store holding one of its keys, if any. */
    val signer: String? = null,
) {
    val multisig get() = watch == WatchKind.MULTISIG
    /** No key on this phone: spending goes out as a PSBT for other signers. */
    val watchOnly get() = watch != null && signer == null
    val seed get() = watch == null
    /** A spending wallet from an extended private key: no phrase to show or back up. */
    val keyOnly get() = seed && words == 0
    val path get() = when {
        watch == WatchKind.ADDRESS -> null
        multisig -> Descriptors.cosignerPath(chain)
        else -> script?.path(chain)
    }
    val quorum get() = descriptors?.let { Descriptors.quorum(it.external) }
}

/**
 * Device-local, in its own prefs file: these wallets aren't tied to the LaWallet account, so logging out
 * (Prefs.clearSession) must never wipe a recovery phrase. There's always one primary while any exist.
 */
class OnchainStore(context: Context) {
    private val sp = context.getSharedPreferences("onchain", Context.MODE_PRIVATE)
    private val json = io.lawallet.wallet.core.relaxedJson
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
        // A vault that signed with this seed's key now has no key here: it spends by PSBT only.
        save(_wallets.value.filter { it.id != id }.map { if (it.signer == id) it.copy(signer = null) else it })
    }

    private fun rawSecret(id: String) = sp.getString("secret:$id", null)?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }

    /** Phrase words (and passphrase), or null (also for a wallet made from a private key). Blocking (Keystore). */
    fun secret(id: String): Pair<List<String>, String?>? = rawSecret(id)?.takeIf { !it.startsWith(KEY) }
        ?.split('\n')?.let { it[0].split(' ') to it.getOrNull(1)?.takeIf(String::isNotEmpty) }

    /** The extended private key a wallet was imported from, or null. Blocking (Keystore). */
    fun privateKey(id: String): String? = rawSecret(id)?.takeIf { it.startsWith(KEY) }?.removePrefix(KEY)

    // ---- settings: device-local too (which server sees this phone's addresses is about the phone, not the account) -

    private val _electrum = Chain.entries.associateWith { MutableStateFlow(sp.getString("electrum:${it.name}", null) ?: it.electrum) }
    fun electrum(chain: Chain): StateFlow<String> = _electrum.getValue(chain)
    /** Null resets to the default server. */
    fun setElectrum(chain: Chain, url: String?) {
        _electrum.getValue(chain).value = url ?: chain.electrum
        sp.edit { if (url == null) remove("electrum:${chain.name}") else putString("electrum:${chain.name}", url) }
    }

    companion object {
        /** How a private-key secret is told from a phrase: [add] it as `KEY + xprv`. */
        const val KEY = "xprv:"
    }

    private val _signet = MutableStateFlow(sp.getBoolean("signet", false))
    /** Signet is offered when creating or importing (wallets already on signet show regardless). */
    val signet: StateFlow<Boolean> = _signet
    fun setSignet(v: Boolean) { _signet.value = v; sp.edit { putBoolean("signet", v) } }
}

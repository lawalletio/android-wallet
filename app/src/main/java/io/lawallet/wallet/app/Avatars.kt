package io.lawallet.wallet.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A Lightning address's Nostr face (via its NIP-05): display name (or name) and picture. */
@Serializable data class AddressProfile(val name: String? = null, val picture: String? = null, val fetchedAt: Long = 0)

fun domainAvatar(domain: String) = "https://raw.githubusercontent.com/lawalletio/static/main/public/img/domains/${if (domain == "lawallet.io") "lawallet.ar" else domain}.png"

/**
 * Recipient avatars by Lightning Address (payment-destinations.ts `useAddressAvatar`).
 * LUD-06 metadata images are base64 `data:` URIs, which Coil only memory-caches, so each one is
 * written to a file once; http pictures and domain logos ride Coil's disk cache.
 */
class Avatars(context: Context) {
    private val dir = File(context.filesDir, "avatars")
    // Listed once so rows never touch the disk while composing.
    private val saved: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { dir.list()?.let { addAll(it) } }

    private val profilesFile = File(context.filesDir, "address-profiles.json")
    private val profilesSer = MapSerializer(String.serializer(), AddressProfile.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    // Read once at startup (a few KB) so the first frame already has every name and face seen before.
    private val _profiles = MutableStateFlow(runCatching { json.decodeFromString(profilesSer, profilesFile.readText()) }.getOrNull() ?: emptyMap())
    /** Keyed by [key]. */
    val profiles: StateFlow<Map<String, AddressProfile>> = _profiles

    fun key(address: String) = address.trim().lowercase().replace(Regex("[^a-z0-9@._-]"), "_")
    private fun name(address: String) = key(address)

    /**
     * Fetches [address]'s profile unless the cached one is younger than [ttlMs]. A null from [fetch]
     * (offline, no NIP-05, no kind 0) keeps what is cached and tries again next time.
     */
    suspend fun refresh(address: String, ttlMs: Long = 12 * 60 * 60 * 1000L, fetch: suspend () -> AddressProfile?) {
        val k = key(address)
        val old = _profiles.value[k]
        if (old != null && System.currentTimeMillis() - old.fetchedAt < ttlMs) return
        val fresh = fetch() ?: return
        _profiles.update { it + (k to fresh.copy(fetchedAt = System.currentTimeMillis())) }
        writeLock.withLock { withContext(Dispatchers.IO) { profilesFile.writeText(json.encodeToString(profilesSer, _profiles.value)) } }
    }

    suspend fun save(address: String, dataUri: String?) {
        val bytes = dataUri?.takeIf { it.startsWith("data:image/") }?.substringAfter("base64,", "")
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: return
        val name = name(address)
        withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, name).writeBytes(bytes) }
        saved += name
    }

    /** Cached Nostr picture, then the contact's picture, then the saved LUD-06 image, then the domain logo. */
    fun of(address: String, picture: String? = null): String =
        _profiles.value[key(address)]?.picture?.takeIf(String::isNotBlank) ?: picture
            ?: name(address).let { if (it in saved) "file://${File(dir, it).absolutePath}" else null }
            ?: domainAvatar(address.substringAfter('@'))

    fun clear() { saved.clear(); dir.deleteRecursively(); _profiles.value = emptyMap(); profilesFile.delete() }
}

/** Looks up [address]'s Nostr profile (its NIP-05 → kind 0) into [Avatars]' cache, unless it is younger than [ttlMs]. */
suspend fun AppContainer.refreshAddressProfile(address: String, ttlMs: Long = 12 * 60 * 60 * 1000L) = avatars.refresh(address, ttlMs) {
    lnurl.nip05Pubkey(address)?.let { pk -> runCatching { session.api.value.profiles(listOf(pk)).firstOrNull() }.getOrNull() }
        ?.let { p -> AddressProfile(p.displayName?.takeIf(String::isNotBlank) ?: p.name?.takeIf(String::isNotBlank), p.picture?.takeIf(String::isNotBlank)) }
}

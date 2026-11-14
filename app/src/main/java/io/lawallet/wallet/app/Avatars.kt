package io.lawallet.wallet.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

private const val FAVICON_MAX = 512_000
private val DOMAIN = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$")

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
            ?: domainLogo(address.substringAfter('@'))

    // Domain logos: LaWallet's static set, else the domain's own favicon saved here as "@domain" (no address key
    // starts with "@"). "@domain.static" marks a domain the set has. Bumps [domainTick] when a favicon lands.
    private val _domainTick = MutableStateFlow(0)
    val domainTick: StateFlow<Int> = _domainTick
    private val checking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [domain]'s saved favicon when it has one, else its logo in the static set. */
    fun domainLogo(domain: String): String {
        val d = domain.trim().lowercase()
        return if ("@$d" in saved) "file://${File(dir, "@$d").absolutePath}" else domainAvatar(d)
    }

    /**
     * Once per domain (and per launch while it fails): the static set has [domain]'s logo, or its favicon
     * (`/favicon.ico`, then `/apple-touch-icon.png`) is fetched, checked to decode, and kept on disk.
     */
    suspend fun ensureDomainLogo(http: OkHttpClient, domain: String) {
        val d = domain.trim().lowercase().takeIf { DOMAIN.matches(it) } ?: return
        if ("@$d" in saved || "@$d.static" in saved || !checking.add(d)) return
        withContext(Dispatchers.IO) {
            val known = runCatching { http.newCall(Request.Builder().url(domainAvatar(d)).head().build()).execute().use { it.code } }.getOrNull() ?: return@withContext
            dir.mkdirs()
            if (known == 200) { File(dir, "@$d.static").createNewFile(); saved += "@$d.static"; return@withContext }
            for (path in listOf("/favicon.ico", "/apple-touch-icon.png")) {
                // Read at most the cap (+1 to spot a bigger one), never a whole oversized body.
                val bytes = runCatching {
                    http.newCall(Request.Builder().url("https://$d$path").build()).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        val src = r.body.source()
                        src.request(FAVICON_MAX + 1L)
                        if (src.buffer.size > FAVICON_MAX) null else src.buffer.readByteArray()
                    }
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0) continue
                File(dir, "@$d").writeBytes(bytes); saved += "@$d"
                _domainTick.update { it + 1 }
                break
            }
        }
    }

    fun clear() { saved.clear(); dir.deleteRecursively(); _profiles.value = emptyMap(); profilesFile.delete() }
}

/** Makes sure [domain] has a logo to show: LaWallet's static one, or its own favicon saved on the device. */
suspend fun AppContainer.ensureDomainLogo(domain: String) = avatars.ensureDomainLogo(http, domain)

/**
 * Looks up [address]'s Nostr profile (its NIP-05 → kind 0) into [Avatars]' cache, unless it is younger than [ttlMs];
 * its domain's logo (the fallback face) is made sure of too.
 */
suspend fun AppContainer.refreshAddressProfile(address: String, ttlMs: Long = 12 * 60 * 60 * 1000L) = coroutineScope {
    launch { ensureDomainLogo(address.substringAfter('@')) }
    avatars.refresh(address, ttlMs) {
        lnurl.nip05Pubkey(address)?.let { pk -> runCatching { session.api.value.profiles(listOf(pk)).firstOrNull() }.getOrNull() }
            ?.let { p -> AddressProfile(p.displayName?.takeIf(String::isNotBlank) ?: p.name?.takeIf(String::isNotBlank), p.picture?.takeIf(String::isNotBlank)) }
    }
}

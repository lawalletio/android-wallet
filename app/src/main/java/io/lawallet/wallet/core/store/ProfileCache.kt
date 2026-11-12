package io.lawallet.wallet.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Kind-0 profile pictures per pubkey, seeded synchronously from [load] so the first frame has them.
 * [refresh] hits the network only past [ttlMs] (or when forced); otherwise it is a no-op.
 */
class ProfileCache(
    private val load: () -> Map<String, Entry>,
    private val save: (Map<String, Entry>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 12 * 60 * 60 * 1000L,
) {
    @Serializable data class Entry(val picture: String? = null, val fetchedAt: Long = 0)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Entry>> = _entries
    private val lock = Mutex()

    /** Throws whatever [fetch] throws; the cached entry is kept. */
    suspend fun refresh(pubkey: String, force: Boolean = false, fetch: suspend (String) -> String?) = lock.withLock {
        val old = _entries.value[pubkey]
        if (!force && old != null && now() - old.fetchedAt < ttlMs) return@withLock
        val picture = fetch(pubkey) ?: old?.picture
        _entries.value = _entries.value + (pubkey to Entry(picture, now()))
        save(_entries.value)
    }

    /** Our own avatar just changed: show it now, not at the next fetch. */
    suspend fun put(pubkey: String, picture: String?) = lock.withLock {
        _entries.value = _entries.value + (pubkey to Entry(picture, now()))
        save(_entries.value)
    }

    fun clear() { _entries.value = emptyMap() }
}

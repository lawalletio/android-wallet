package io.lawallet.wallet.core

import io.lawallet.wallet.core.store.ProfileCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Within the TTL the cache answers alone; past it, exactly one fetch refreshes it. */
class ProfileCacheTest {
    @Test
    fun refreshesOnlyPastTtl() = runBlocking {
        var clock = 1_000L
        var saved: Map<String, ProfileCache.Entry> = emptyMap()
        val cache = ProfileCache({ mapOf("pk" to ProfileCache.Entry("a.png", 1_000L)) }, { saved = it }, { clock }, ttlMs = 100)
        var calls = 0
        val fetch: suspend (String) -> String? = { calls++; "b.png" }

        assertEquals("a.png", cache.entries.value["pk"]?.picture)
        clock = 1_099; cache.refresh("pk", fetch = fetch)
        assertEquals(0, calls)

        clock = 1_100; cache.refresh("pk", fetch = fetch); cache.refresh("pk", fetch = fetch)
        assertEquals(1, calls)
        assertEquals("b.png", cache.entries.value["pk"]?.picture)
        assertEquals("b.png", saved["pk"]?.picture)

        cache.refresh("pk", force = true, fetch = fetch)
        assertEquals(2, calls)
    }
}

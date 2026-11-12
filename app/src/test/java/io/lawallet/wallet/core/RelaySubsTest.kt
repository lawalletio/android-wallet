package io.lawallet.wallet.core

import io.lawallet.wallet.core.nwc.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Subscription refcount/dedup, the per-relay REQ cap and backoff, without a socket. */
class RelaySubsTest {
    private val sent = mutableListOf<String>()
    // Accepts the TCP connection but never answers the upgrade: connect() stays pending, no onOpen/onFailure.
    private val server = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }

    @After fun stop() = server.shutdown()

    private fun relay() = Relay(server.url("/").toString().replace("http", "ws"), OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build(), CoroutineScope(Dispatchers.Unconfined)).also { r ->
        r.sink = { sent += it }
        r._connected.value = true
    }

    private fun filter(kind: Int) = buildJsonObject { put("kinds", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(kind)))) }

    @Test fun identicalSubscribeIsSentOnceAndClosedByLastHolder() {
        val r = relay()
        r.subscribe("a", filter(1))
        r.subscribe("a", filter(1))
        assertEquals(1, sent.count { it.startsWith("[\"REQ\"") })
        r.unsubscribe("a")
        assertEquals(0, sent.count { it.startsWith("[\"CLOSE\"") })
        r.unsubscribe("a")
        assertEquals(listOf("[\"CLOSE\",\"a\"]"), sent.filter { it.startsWith("[\"CLOSE\"") })
    }

    @Test fun walletSwitchClosesOldThenOpensNew() {
        val r = relay()
        r.subscribe("ntf-old", filter(23197))
        r.unsubscribe("ntf-old")
        r.subscribe("ntf-new", filter(23197))
        assertEquals(listOf("REQ", "CLOSE", "REQ"), sent.map { it.substringAfter("[\"").substringBefore('"') })
        assertEquals(listOf("ntf-new"), r.openSubIds())
    }

    @Test fun capQueuesExtraSubsUntilASlotFrees() {
        val r = relay()
        repeat(Relay.MAX_SUBS + 2) { r.subscribe("s$it", filter(it)) }
        assertEquals(Relay.MAX_SUBS, r.openSubIds().size)
        r.unsubscribe("s0")
        assertEquals(Relay.MAX_SUBS, r.openSubIds().size)
        assertTrue("s${Relay.MAX_SUBS}" in r.openSubIds())
    }

    @Test fun tooManySubsLowersTheCapInsteadOfRetrying() {
        val r = relay()
        repeat(4) { r.subscribe("s$it", filter(it)) }
        val reqs = sent.size
        r.onFrame("""["CLOSED","s3","error: too many concurrent REQs"]""")
        assertEquals(3, r.maxSubs)
        assertEquals(reqs, sent.size) // not re-sent until another sub closes
        r.unsubscribe("s0")
        assertTrue("s3" in r.openSubIds())
    }

    @Test fun backoffGrowsCapsAndJitters() {
        val mid = Random(0)
        assertTrue(Relay.backoffMs(0, mid) in 750L..1250L)
        assertTrue(Relay.backoffMs(3, mid) in 6000L..10_000L)
        assertTrue(Relay.backoffMs(50, mid) in 22_500L..37_500L)
        assertTrue(Relay.isRateLimit("rate-limited: slow down"))
        assertTrue(Relay.isTooMany("too many subscriptions"))
    }
}

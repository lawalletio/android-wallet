package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Electrum
import io.lawallet.wallet.core.btc.ElectrumException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A tiny Electrum server: answers batches in reverse order (ids must match), can push and drop. */
private class FakeElectrum {
    val server = ServerSocket(0)
    val statuses = mutableMapOf("a" to "sa", "b" to null, "c" to "sc")
    /** Scripthashes subscribed, per connection. */
    val subscribed = CopyOnWriteArrayList<MutableList<String>>()
    @Volatile private var out: PrintWriter? = null
    @Volatile private var conn: Socket? = null

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                conn = s
                val w = PrintWriter(s.getOutputStream(), true).also { out = it }
                val subs = mutableListOf<String>().also { subscribed.add(it) }
                thread(isDaemon = true) {
                    runCatching {
                        s.getInputStream().bufferedReader().forEachLine { line ->
                            val msg = Json.parseToJsonElement(line)
                            val reply = if (msg is JsonArray) JsonArray(msg.map { answer(it.jsonObject, subs) }.reversed()) else answer(msg.jsonObject, subs)
                            synchronized(w) { w.println(reply.toString()) }
                        }
                    }
                }
            }
        }
    }

    private fun answer(req: JsonObject, subs: MutableList<String>): JsonElement {
        val id = req["id"]!!
        val params = req["params"]!!.jsonArray
        val result: String = when (req["method"]!!.jsonPrimitive.content) {
            "server.version" -> """["fake","1.4"]"""
            "blockchain.headers.subscribe" -> """{"height":100,"hex":"00"}"""
            "blockchain.scripthash.subscribe" -> params[0].jsonPrimitive.content.let { subs.add(it); statuses[it]?.let { s -> "\"$s\"" } ?: "null" }
            "blockchain.estimatefee" -> "0.00002"
            "server.ping" -> "null"
            else -> return Json.parseToJsonElement("""{"id":$id,"error":{"code":-32601,"message":"unknown method"}}""")
        }
        return Json.parseToJsonElement("""{"id":$id,"result":$result}""")
    }

    fun push(line: String) { out?.let { synchronized(it) { it.println(line) } } }
    fun drop() { conn?.close() }
    val url get() = "tcp://127.0.0.1:${server.localPort}"
}

class ElectrumTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeElectrum()

    @After fun tearDown() { scope.cancel(); fake.server.close() }

    @Test fun subscribesNotifiesAndResubscribes() = runBlocking<Unit> {
        val e = Electrum(fake.url, scope, allowTcp = true)
        e.watch(listOf("a", "b"))
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.status.take(2).toList() }
        e.start()
        withTimeout(5_000) { e.connected.first { it } }
        assertEquals(setOf("a" to "sa", "b" to null), withTimeout(5_000) { first.await() }.toSet())
        assertEquals(100, e.tip.value)

        // Calls work, matched by id; a server error is an ElectrumException.
        assertEquals(2.0, Electrum.satPerVb(e.call("blockchain.estimatefee", buildJsonArray { add(JsonPrimitive(2)) }).jsonPrimitive.double)!!, 1e-9)
        assertTrue(runCatching { e.call("nope") }.exceptionOrNull() is ElectrumException)

        // A change notification, and a new tip.
        val changed = async(start = CoroutineStart.UNDISPATCHED) { e.status.first { it.first == "a" } }
        fake.push("""{"jsonrpc":"2.0","method":"blockchain.scripthash.subscribe","params":["a","sa2"]}""")
        assertEquals("a" to "sa2", withTimeout(5_000) { changed.await() })
        fake.push("""{"method":"blockchain.headers.subscribe","params":[{"height":101,"hex":"00"}]}""")
        withTimeout(5_000) { e.tip.first { it == 101 } }

        // Watching more while connected subscribes at once.
        val c = async(start = CoroutineStart.UNDISPATCHED) { e.status.first { it.first == "c" } }
        e.watch(listOf("c", "a"))
        assertEquals("c" to "sc", withTimeout(5_000) { c.await() })

        // Dropped: it reconnects and re-subscribes everything it watches.
        fake.drop()
        withTimeout(5_000) { e.connected.first { !it } }
        withTimeout(10_000) { e.connected.first { it } }
        assertEquals(setOf("a", "b", "c"), fake.subscribed.last().toSet())
        e.stop()
    }

    @Test fun refusesPlainTcpUnlessAllowed() = runBlocking<Unit> {
        val e = Electrum(fake.url, scope)
        e.start()
        withTimeout(5_000) { e.lastError.first { it != null } }
        assertFalse(e.connected.value)
        e.stop()
    }

    @Test fun parsesUrls() {
        assertEquals(Triple("ssl", "electrum.blockstream.info", 50002), Electrum.parse("ssl://electrum.blockstream.info:50002"))
        assertEquals(Triple("ssl", "mempool.space", 60602), Electrum.parse("mempool.space:60602"))
        assertEquals(Triple("tcp", "10.0.2.2", 50001), Electrum.parse("tcp://10.0.2.2:50001"))
        assertTrue(runCatching { Electrum.parse("https://x/y:1") }.isFailure)
        assertTrue(runCatching { Electrum.parse("host") }.isFailure)
        assertEquals(null, Electrum.satPerVb(-1.0))
    }
}

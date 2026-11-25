package io.lawallet.wallet.core.btc

import io.lawallet.wallet.core.diag.Perf
import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.nwc.Relay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ElectrumException(message: String) : Exception(message)

/**
 * One Electrum server (protocol 1.4, newline-delimited JSON-RPC over TLS, or plain TCP where allowed), kept open for
 * live updates: `blockchain.headers.subscribe` for the tip and `blockchain.scripthash.subscribe` for every script it
 * [watch]es. On every (re)connect it re-subscribes all of them and reports each status on [status], so the caller
 * compares with what it last synced and syncs only what changed (Sparrow's approach). BDK does the syncing itself.
 * The server learns scripthashes, never keys or descriptors.
 */
class Electrum(val url: String, private val scope: CoroutineScope, private val allowTcp: Boolean = false) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _tip = MutableStateFlow<Int?>(null)
    val tip: StateFlow<Int?> = _tip
    private val _status = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 1024)
    /** (scripthash, status) on every subscribe answer and every change notification. A null status: no history. */
    val status: SharedFlow<Pair<String, String?>> = _status
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val ids = AtomicInteger()
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    private var job: Job? = null

    fun start() = synchronized(this) { if (job?.isActive != true) job = scope.launch(Dispatchers.IO) { run() } }

    fun stop() = synchronized(this) {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
    }

    /** Adds scripthashes to the subscription set; new ones are subscribed right away when connected. */
    fun watch(hashes: Collection<String>) {
        val fresh = hashes.filter { watched.add(it) }
        if (fresh.isNotEmpty() && _connected.value) scope.launch(Dispatchers.IO) { runCatching { subscribe(fresh) } }
    }

    /** One call, waiting for the connection first. Throws [ElectrumException] for a server error, IOException offline. */
    suspend fun call(method: String, params: JsonArray = JsonArray(emptyList()), timeoutMs: Long = 20_000): JsonElement {
        withTimeout(timeoutMs) { _connected.first { it } }
        return request(method, params, timeoutMs)
    }

    // ---- connection ---------------------------------------------------------------------------------------------

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                val s = open()
                socket = s
                coroutineScope {
                    launch { read(s) }
                    // Closing the socket is what unblocks the reader, whichever side fails (or stop() cancels).
                    try {
                        val t0 = System.currentTimeMillis()
                        request("server.version", buildJsonArray { add(JsonPrimitive("LaWallet")); add(JsonPrimitive("1.4")) })
                        _tip.value = request("blockchain.headers.subscribe").jsonObject["height"]?.jsonPrimitive?.int
                        subscribe(watched.toList())
                        _connected.value = true
                        _lastError.value = null
                        attempt = 0
                        Perf.record("electrum", mapOf("method" to "connect", "ms" to System.currentTimeMillis() - t0))
                        while (true) { delay(PING_MS); request("server.ping") }
                    } finally {
                        runCatching { s.close() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _connected.value = false
                runCatching { socket?.close() }
                socket = null
                pending.values.forEach { it.completeExceptionally(IOException("electrum: disconnected")) }
                pending.clear()
            }
            val wait = Relay.backoffMs(attempt++)
            Trail.add("electrum", "reconnect in ${wait}ms (attempt $attempt)")
            delay(wait)
        }
    }

    private fun open(): Socket {
        val (scheme, host, port) = parse(url)
        if (scheme == "tcp" && !allowTcp) throw IOException("electrum: plain tcp not allowed")
        val raw = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_MS); soTimeout = 0; tcpNoDelay = true }
        if (scheme == "tcp") return raw
        return (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).let { it as SSLSocket }.apply {
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }
    }

    private fun read(s: Socket) {
        val input = s.getInputStream().bufferedReader()
        while (true) {
            val line = input.readLine() ?: throw IOException("electrum: closed")
            if (line.isBlank()) continue
            val msg = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: continue
            if (msg is JsonArray) msg.forEach(::handle) else handle(msg)
        }
    }

    private fun handle(msg: JsonElement) {
        val o = msg as? JsonObject ?: return
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (id != null) {
            val d = pending.remove(id) ?: return
            val err = o["error"]?.takeIf { it !is JsonNull }
            if (err != null) d.completeExceptionally(ElectrumException((err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()))
            else d.complete(o["result"] ?: JsonNull)
            return
        }
        val params = o["params"] as? JsonArray ?: return
        when ((o["method"] as? JsonPrimitive)?.contentOrNull) {
            "blockchain.headers.subscribe" -> params.firstOrNull()?.jsonObject?.get("height")?.jsonPrimitive?.int?.let { _tip.value = it }
            "blockchain.scripthash.subscribe" -> {
                val hash = params.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                _status.tryEmit(hash to params.getOrNull(1)?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    private fun send(text: String) {
        val s = socket ?: throw IOException("electrum: not connected")
        synchronized(writeLock) { s.getOutputStream().apply { write((text + "\n").toByteArray()); flush() } }
    }

    private suspend fun request(method: String, params: JsonArray = JsonArray(emptyList()), timeoutMs: Long = 20_000): JsonElement {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<JsonElement>().also { pending[id] = it }
        val t0 = System.currentTimeMillis()
        try {
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
            return withTimeout(timeoutMs) { d.await() }.also {
                if (method != "server.ping") Perf.record("electrum", mapOf("method" to method, "ms" to System.currentTimeMillis() - t0))
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Subscribes [hashes] in batches (one JSON array per batch, answers matched by id) and reports their statuses. */
    private suspend fun subscribe(hashes: List<String>) {
        for (chunk in hashes.chunked(BATCH)) {
            val calls = chunk.associateBy { ids.incrementAndGet() }
            val waits = calls.keys.associateWith { CompletableDeferred<JsonElement>().also { d -> pending[it] = d } }
            try {
                send(JsonArray(calls.map { (id, h) -> buildJsonObject { put("id", id); put("method", "blockchain.scripthash.subscribe"); put("params", buildJsonArray { add(JsonPrimitive(h)) }) } }).toString())
                withTimeout(30_000) {
                    for ((id, h) in calls) _status.emit(h to (waits.getValue(id).await() as? JsonPrimitive)?.contentOrNull)
                }
            } finally {
                calls.keys.forEach(pending::remove)
            }
        }
    }

    companion object {
        private const val PING_MS = 60_000L
        private const val CONNECT_MS = 10_000
        // electrs-esplora (blockstream.info) drops the connection on a JSON batch over ~20 requests.
        private const val BATCH = 15
        private val json = Json { ignoreUnknownKeys = true }

        /** `ssl://host:port` or `tcp://host:port`; a bare `host:port` means TLS. Throws on anything else. */
        fun parse(url: String): Triple<String, String, Int> {
            val u = url.trim()
            val scheme = u.substringBefore("://", "ssl").lowercase()
            val rest = u.substringAfter("://")
            val host = rest.substringBeforeLast(':')
            val port = rest.substringAfterLast(':', "").toIntOrNull()
            require(scheme in setOf("ssl", "tcp") && host.isNotBlank() && '/' !in host && port != null && port in 1..65535) { "bad electrum url" }
            return Triple(scheme, host, port)
        }

        /** Electrum fee estimates are BTC/kvB; -1 means the server has no estimate. */
        fun satPerVb(btcPerKb: Double): Double? = btcPerKb.takeIf { it > 0 }?.let { it * 100_000 }
    }
}


package io.lawallet.wallet.core.nwc

import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.nostr.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Process-wide relay connections, one per URL: wallets, screens and signers all share them, so a
 * wallet switch or a screen leaving only swaps subscriptions, never the socket.
 */
object RelayPool {
    private val relays = ConcurrentHashMap<String, Relay>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ponytail: the first caller's OkHttpClient wins; there is one per process anyway.
    fun get(url: String, http: OkHttpClient): Relay = relays.getOrPut(url.trimEnd('/')) { Relay(url, http, scope) }

    /** App came back: reconnect dropped relays now instead of waiting out their backoff. */
    fun foreground() = relays.values.forEach { it.reconnectNow() }

    /** App left: drop sockets nobody listens on (a wallet's notification stream keeps its relay up). */
    fun background() = relays.values.forEach { it.disconnectIfIdle() }
}

/**
 * One Nostr relay connection: reconnects on a real drop with jittered exponential backoff, replays
 * open subscriptions (since the drop), and keeps at most [maxSubs] REQs open, queueing the rest.
 * Subscriptions are reference-counted by id: the same id + filter is sent once and CLOSEd when
 * the last holder unsubscribes. Rate-limit CLOSED/NOTICE messages pause REQs instead of retrying.
 */
class Relay(val url: String, private val http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface Msg {
        data class Event(val subId: String, val event: NostrEvent) : Msg
        data class Eose(val subId: String) : Msg
    }

    internal val _connected = MutableStateFlow(false) // internal: tests drive it without a handshake
    val connected: StateFlow<Boolean> = _connected
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _messages = MutableSharedFlow<Msg>(extraBufferCapacity = 256)
    val messages = _messages.asSharedFlow()

    private class Sub(var filter: JsonObject, var refs: Int = 1, var sent: Boolean = false)

    private val lock = Any()
    private val subs = LinkedHashMap<String, Sub>() // insertion order = REQ queue order
    private val pendingOk = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    @Volatile private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var flushJob: Job? = null
    private var attempt = 0
    /** Epoch seconds the socket last dropped: replayed REQs move their `since` up to it. */
    private var droppedAt = 0L
    private var throttledUntil = 0L
    var maxSubs = MAX_SUBS
        private set

    /** Abstracts the socket so the subscription bookkeeping is unit-testable. */
    internal var sink: (String) -> Unit = { socket?.send(it) }

    fun connect() {
        synchronized(lock) {
            if (socket != null) return
            socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
        }
    }

    internal fun reconnectNow() {
        if (_connected.value || synchronized(lock) { subs.isEmpty() }) return
        reconnectJob?.cancel()
        attempt = 0
        connect()
    }

    internal fun disconnectIfIdle() {
        val ws = synchronized(lock) {
            if (subs.isNotEmpty() || pendingOk.isNotEmpty()) return
            socket.also { socket = null }
        } ?: return
        reconnectJob?.cancel()
        _connected.value = false
        ws.close(1000, null)
    }

    fun subscribe(subId: String, filter: JsonObject) {
        synchronized(lock) {
            val s = subs[subId]
            when {
                s == null -> subs[subId] = Sub(filter)
                s.filter == filter -> { s.refs++; return } // already open: nothing to re-send
                else -> { s.refs++; s.filter = filter; s.sent = false } // NIP-01: a REQ with the same id replaces it
            }
        }
        connect()
        flush()
    }

    fun unsubscribe(subId: String) {
        val wasSent = synchronized(lock) {
            val s = subs[subId] ?: return
            if (--s.refs > 0) return
            subs.remove(subId)
            s.sent
        }
        if (wasSent && _connected.value) sink(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString())
        flush()
    }

    internal fun openSubIds(): List<String> = synchronized(lock) { subs.filterValues { it.sent }.keys.toList() }

    /** Sends queued REQs up to [maxSubs], unless the relay asked us to slow down. */
    internal fun flush() {
        if (_connected.value) drain()
    }

    private fun drain() {
        val wait = throttledUntil - System.currentTimeMillis()
        if (wait > 0) {
            if (flushJob?.isActive != true) flushJob = scope.launch { delay(wait); flush() }
            return
        }
        val out = synchronized(lock) {
            var open = subs.values.count { it.sent }
            subs.entries.filter { !it.value.sent }.takeWhile { open++ < maxSubs }.map { (id, s) ->
                s.sent = true
                JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(id), s.filter)).toString()
            }
        }
        out.forEach(sink)
    }

    /** Publishes and waits for the relay's OK. Throws when rejected or timed out. */
    suspend fun publish(event: NostrEvent, timeoutMs: Long = 15_000) {
        connect()
        val ok = CompletableDeferred<Pair<Boolean, String>>()
        pendingOk[event.id] = ok
        try {
            withTimeout(timeoutMs) {
                connected.first { it }
                sink(JsonArray(listOf(JsonPrimitive("EVENT"), NostrEvent.json.parseToJsonElement(event.toJson()))).toString())
                val (accepted, message) = ok.await()
                if (!accepted) {
                    if (isRateLimit(message)) throttle()
                    throw RelayException("Relay rejected event: $message")
                }
            }
        } finally {
            pendingOk.remove(event.id)
        }
    }

    private fun throttle() {
        val backoff = backoffMs(attempt.coerceAtLeast(1))
        throttledUntil = maxOf(throttledUntil, System.currentTimeMillis() + backoff)
        Trail.add("relay", "rate-limited, pausing ${backoff}ms", error = true)
    }

    /** Handles one relay frame; internal so tests can feed CLOSED/NOTICE without a socket. */
    internal fun onFrame(text: String) {
        val arr = runCatching { NostrEvent.json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
        when (arr.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
            "EVENT" -> {
                val subId = arr[1].jsonPrimitive.content
                val ev = runCatching { NostrEvent.json.decodeFromJsonElement(NostrEvent.serializer(), arr[2]) }.getOrNull() ?: return
                if (ev.verify()) _messages.tryEmit(Msg.Event(subId, ev))
            }
            "EOSE" -> _messages.tryEmit(Msg.Eose(arr[1].jsonPrimitive.content))
            "OK" -> pendingOk[arr[1].jsonPrimitive.content]?.complete(
                (arr.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false) to (arr.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: "")
            )
            "CLOSED" -> {
                val subId = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                val reason = arr.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: ""
                Trail.add("relay", "closed sub: ${reason.substringBefore(':')}", error = true)
                synchronized(lock) {
                    val s = subs[subId] ?: return
                    s.sent = false // still wanted: queued again for the next flush
                    if (isTooMany(reason)) maxSubs = (subs.values.count { it.sent }).coerceAtLeast(1)
                    else if (!isRateLimit(reason)) subs.remove(subId) // refused for good (auth, invalid…)
                }
                if (isRateLimit(reason)) throttle()
                flush()
            }
            "NOTICE" -> {
                val msg = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: ""
                if (isRateLimit(msg) || isTooMany(msg)) throttle()
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Trail.add("relay", "open")
            attempt = 0
            _lastError.value = null
            synchronized(lock) {
                subs.values.forEach { s ->
                    s.sent = false
                    // Resubscribing after a drop: only what arrived since (minus clock slack), not the whole store.
                    val since = s.filter["since"]?.jsonPrimitive?.longOrNull
                    if (since != null && droppedAt - 60 > since) s.filter = JsonObject(s.filter + ("since" to JsonPrimitive(droppedAt - 60)))
                }
            }
            // Subscriptions first: a publish waiting on `connected` must not beat the REQ for its reply.
            drain()
            _connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Trail.add("relay", "closed code=$code", error = code != 1000)
            webSocket.close(1000, null)
            dropped(webSocket, if (code == 1000) null else "relay closed ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Class name only: the message names the relay host.
            if (socket === webSocket) Trail.add("relay", "failure ${t.javaClass.simpleName}${response?.let { " http=${it.code}" } ?: ""}", error = true)
            dropped(webSocket, t.message ?: "connection failed")
        }
    }

    private fun dropped(ws: WebSocket, error: String?) {
        synchronized(lock) {
            if (socket !== ws) return // replaced, or closed on purpose by disconnectIfIdle
            socket = null
            droppedAt = System.currentTimeMillis() / 1000
        }
        _connected.value = false
        if (error != null) _lastError.value = error
        if (synchronized(lock) { subs.isEmpty() } && pendingOk.isEmpty()) return // nobody listening: reconnect lazily on next use
        val backoff = backoffMs(attempt)
        attempt++
        Trail.add("relay", "reconnect in ${backoff}ms (attempt $attempt)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch { delay(backoff); connect() }
    }

    companion object {
        /** Most public relays allow 10–20 concurrent REQs per connection; stay under the low end. */
        const val MAX_SUBS = 10

        /** 1s, 2s, 4s… capped at 30s, ±25% jitter so a fleet of clients doesn't reconnect in lockstep. */
        fun backoffMs(attempt: Int, random: Random = Random): Long {
            val base = (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
            return base + (base * (random.nextDouble() - 0.5) / 2).toLong()
        }

        fun isRateLimit(msg: String) = msg.startsWith("rate-limited") || msg.contains("rate limit", ignoreCase = true) || msg.contains("slow down", ignoreCase = true)
        fun isTooMany(msg: String) = msg.contains("too many", ignoreCase = true) || msg.contains("max", ignoreCase = true) && msg.contains("sub", ignoreCase = true)
    }
}

class RelayException(message: String) : Exception(message)

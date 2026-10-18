package io.lawallet.wallet.core.nwc

import io.lawallet.wallet.core.nostr.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap

/** One Nostr relay connection that reconnects with backoff and replays open subscriptions. */
class Relay(val url: String, private val http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface Msg {
        data class Event(val subId: String, val event: NostrEvent) : Msg
        data class Eose(val subId: String) : Msg
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _messages = MutableSharedFlow<Msg>(extraBufferCapacity = 256)
    val messages = _messages.asSharedFlow()

    private val subs = ConcurrentHashMap<String, JsonObject>()
    private val pendingOk = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var reconnectJob: Job? = null
    private var attempt = 0

    fun connect() {
        if (closed || socket != null) return
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        closed = true
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _connected.value = false
    }

    fun subscribe(subId: String, filter: JsonObject) {
        subs[subId] = filter
        connect()
        if (_connected.value) send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subId), filter)))
    }

    fun unsubscribe(subId: String) {
        if (subs.remove(subId) != null && _connected.value) send(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))))
    }

    /** Publishes and waits for the relay's OK. Throws when rejected or timed out. */
    suspend fun publish(event: NostrEvent, timeoutMs: Long = 15_000) {
        connect()
        val ok = CompletableDeferred<Pair<Boolean, String>>()
        pendingOk[event.id] = ok
        try {
            withTimeout(timeoutMs) {
                connected.first { it }
                send(JsonArray(listOf(JsonPrimitive("EVENT"), NostrEvent.json.parseToJsonElement(event.toJson()))))
                val (accepted, message) = ok.await()
                if (!accepted) throw RelayException("Relay rejected event: $message")
            }
        } finally {
            pendingOk.remove(event.id)
        }
    }

    private fun send(msg: JsonElement) {
        socket?.send(msg.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _connected.value = true
            _lastError.value = null
            subs.forEach { (id, filter) -> send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(id), filter))) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
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
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket, if (code == 1000) null else "relay closed ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t.message ?: "connection failed")
    }

    private fun dropped(ws: WebSocket, error: String?) {
        if (socket !== ws) return
        socket = null
        _connected.value = false
        if (error != null) _lastError.value = error
        if (closed) return
        val backoff = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000)
        attempt++
        reconnectJob = scope.launch { delay(backoff); connect() }
    }
}

class RelayException(message: String) : Exception(message)

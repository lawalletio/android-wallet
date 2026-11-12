package io.lawallet.wallet.core.auth

import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.Nip44
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.Signer
import io.lawallet.wallet.core.nostr.randomBytes
import io.lawallet.wallet.core.nostr.toHex
import io.lawallet.wallet.core.nwc.Relay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** `bunker://<remote-signer-pubkey>?relay=wss://…&secret=…` — pasted or scanned from the signer. */
data class BunkerUri(val remotePubkey: String, val relays: List<String>, val secret: String?) {
    companion object {
        fun parse(raw: String): BunkerUri {
            val s = raw.trim()
            require(s.startsWith("bunker://", ignoreCase = true)) { "not a bunker:// URI" }
            val (pubkey, params) = splitUri(s.substring("bunker://".length))
            require(pubkey.length == 64 && pubkey.all { it in "0123456789abcdefABCDEF" }) { "invalid signer pubkey" }
            val relays = params.filter { it.first == "relay" }.map { it.second }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
            require(relays.isNotEmpty()) { "missing relay" }
            return BunkerUri(pubkey.lowercase(), relays, params.firstOrNull { it.first == "secret" }?.second?.takeIf { it.isNotEmpty() })
        }
    }
}

/** `nostrconnect://<client-pubkey>?relay=…&secret=…&perms=…&name=…` — shown as a QR for the signer to scan. */
data class NostrConnectUri(val clientPubkey: String, val relays: List<String>, val secret: String, val perms: String?, val name: String?) {
    override fun toString() = buildString {
        append("nostrconnect://").append(clientPubkey).append('?')
        append((relays.map { "relay" to it } + listOf("secret" to secret, "perms" to perms, "name" to name))
            .filter { it.second != null }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") })
    }

    companion object {
        fun parse(raw: String): NostrConnectUri {
            val s = raw.trim()
            require(s.startsWith("nostrconnect://", ignoreCase = true)) { "not a nostrconnect:// URI" }
            val (pubkey, params) = splitUri(s.substring("nostrconnect://".length))
            require(pubkey.length == 64) { "invalid client pubkey" }
            fun p(k: String) = params.firstOrNull { it.first == k }?.second
            return NostrConnectUri(pubkey.lowercase(), params.filter { it.first == "relay" }.map { it.second }, p("secret") ?: error("missing secret"), p("perms"), p("name"))
        }
    }
}

private fun splitUri(rest: String): Pair<String, List<Pair<String, String>>> =
    rest.substringBefore('?').trimEnd('/') to rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

/** What survives a restart: the client key lives Keystore-wrapped next to this, in [Prefs.signerSecretBlob]. */
@Serializable
data class BunkerSession(val remotePubkey: String, val userPubkey: String, val relays: List<String>)

class Nip46Exception(message: String) : Exception(message)

/**
 * NIP-46 client: kind-24133 JSON-RPC to a remote signer over its relays, NIP-44 encrypted (NIP-04
 * once the signer is seen answering in it). One subscription per relay routes replies by id.
 */
class Nip46Client(
    val clientKeys: Keys,
    relayUrls: List<String>,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    /** The signer wants the user to approve in a browser (`auth_url`). */
    var onAuthUrl: (String) -> Unit = {},
) {
    val relayUrls = relayUrls.distinct()
    private val relays = this.relayUrls.map { Relay(it, http, scope) }
    var remotePubkey: String? = null
    @Volatile private var nip04 = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Response>>()
    /** nostrconnect handshake: waits for a `connect` reply carrying our secret, from a signer we don't know yet. */
    @Volatile private var handshake: Pair<String, CompletableDeferred<String>>? = null
    private var listener: Job? = null

    @Synchronized
    private fun listen() {
        if (listener != null) return
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(KIND)) }
            putJsonArray("#p") { add(JsonPrimitive(clientKeys.pubkey)) }
            // No `since`: 24133 is ephemeral (nothing to replay), and a signer whose clock runs behind would be filtered out.
        }
        listener = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            relays.map { it.messages }.merge().filterIsInstance<Relay.Msg.Event>().collect { onEvent(it.event) }
        }
        relays.forEach { it.subscribe(SUB_ID, filter) }
    }

    private fun onEvent(ev: NostrEvent) {
        if (ev.kind != KIND) return
        val known = remotePubkey
        if (known != null && ev.pubkey != known) return
        val usedNip04 = ev.content.contains("?iv=")
        val res = runCatching { decode(decrypt(ev.content, ev.pubkey)) }.getOrNull()
            ?: return Trail.add("nip46", "undecryptable reply", error = true)
        handshake?.let { (secret, done) ->
            // Spec: the signer echoes our secret. Older signers (pre-2024 Amber, nsecBunker) answer "ack" instead; the
            // reply is still encrypted to our one-off key, which only the QR carried.
            if (known == null && (res.result == secret || res.result == "ack")) { if (usedNip04) nip04 = true; done.complete(ev.pubkey); return }
            if (known == null) Trail.add("nip46", "handshake reply without our secret", error = true)
        }
        if (ev.pubkey != known) return
        if (usedNip04) nip04 = true
        if (res.result == "auth_url" && res.error != null) { Trail.add("nip46", "auth_url"); onAuthUrl(res.error); return }
        pending[res.id]?.complete(res)
    }

    /** Waits for a signer to scan our nostrconnect:// QR and answer with [secret]. Returns its pubkey. */
    suspend fun awaitConnect(secret: String, timeoutMs: Long = 300_000): String {
        val done = CompletableDeferred<String>()
        handshake = secret to done
        listen()
        try {
            return (withTimeoutOrNull(timeoutMs) { done.await() } ?: throw Nip46Exception("No signer connected in time")).also { remotePubkey = it }
        } finally {
            handshake = null
        }
    }

    /** One JSON-RPC round trip. Throws [Nip46Exception] on a signer error or timeout. */
    suspend fun request(method: String, params: List<String>, timeoutMs: Long = 90_000): String = withContext(Dispatchers.Default) {
        val remote = remotePubkey ?: throw Nip46Exception("Not connected to a signer")
        listen()
        val id = randomBytes(8).toHex()
        val reply = CompletableDeferred<Response>()
        pending[id] = reply
        val started = System.currentTimeMillis()
        try {
            val ev = NostrEvent.sign(clientKeys, KIND, encrypt(encodeRequest(id, method, params), remote), listOf(listOf("p", remote)))
            // Any one relay taking it is enough (the signer listens on all); fail fast only when none does.
            val failed = AtomicInteger()
            relays.forEach { r ->
                scope.launch { runCatching { r.publish(ev) }.onFailure { if (failed.incrementAndGet() == relays.size) reply.completeExceptionally(Nip46Exception("Couldn't reach the signer's relays")) } }
            }
            val res = withTimeoutOrNull(timeoutMs) { reply.await() } ?: throw Nip46Exception("The signer did not answer in time")
            if (!res.error.isNullOrEmpty()) throw Nip46Exception(res.error)
            Trail.add("nip46", "$method ok ${System.currentTimeMillis() - started}ms")
            res.result ?: ""
        } catch (e: Nip46Exception) {
            Trail.add("nip46", "$method failed ${System.currentTimeMillis() - started}ms", error = true)
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /** bunker:// flow: `connect` (the signer may answer `ack` or echo the secret). */
    suspend fun connect(secret: String?) {
        val remote = remotePubkey ?: throw Nip46Exception("Not connected to a signer")
        val result = request("connect", listOf(remote, secret ?: "", PERMS), timeoutMs = 180_000)
        if (result != "ack" && result != secret) throw Nip46Exception("The signer refused the connection")
    }

    suspend fun getPublicKey(): String = request("get_public_key", emptyList()).also {
        if (it.length != 64) throw Nip46Exception("The signer returned an invalid public key")
    }

    fun close() {
        listener?.cancel()
        relays.forEach { it.close() }
    }

    private fun encrypt(plain: String, peer: String) =
        if (nip04) Nip04.encrypt(plain, clientKeys, peer) else Nip44.encrypt(plain, clientKeys, peer)

    private fun decrypt(content: String, peer: String) =
        if (content.contains("?iv=")) Nip04.decrypt(content, clientKeys, peer) else Nip44.decrypt(content, clientKeys, peer)

    @Serializable data class Request(val id: String, val method: String, val params: List<String>)
    @Serializable data class Response(val id: String, val result: String? = null, val error: String? = null)

    companion object {
        const val KIND = 24133
        private const val SUB_ID = "nip46"
        /** The only event the app signs remotely: NIP-98 HTTP auth for the LaWallet API. */
        const val PERMS = "sign_event:27235"
        // Both verified to relay kind 24133. relay.nsec.app was unreachable (Nov 2026); signers that use only the first relay pick damus.
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun encodeRequest(id: String, method: String, params: List<String>) = json.encodeToString(Request.serializer(), Request(id, method, params))
        fun decode(plain: String): Response = json.decodeFromString(Response.serializer(), plain)
        fun newSecret() = randomBytes(16).toHex()
    }
}

/** The app's [Signer] backed by a remote NIP-46 signer: every signature is a `sign_event` round trip. */
class Nip46Signer(val client: Nip46Client, override val pubkey: String) : Signer {
    val session get() = BunkerSession(client.remotePubkey!!, pubkey, client.relayUrls)

    override suspend fun sign(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        val unsigned = buildJsonObject {
            put("kind", kind); put("content", content); put("created_at", createdAt); put("pubkey", pubkey)
            putJsonArray("tags") { tags.forEach { t -> add(kotlinx.serialization.json.JsonArray(t.map { JsonPrimitive(it) })) } }
        }.toString()
        val signed = client.request("sign_event", listOf(unsigned))
        val event = runCatching { NostrEvent.json.decodeFromString(NostrEvent.serializer(), signed) }.getOrNull()
            ?: throw Nip46Exception("The signer returned an invalid event")
        if (event.pubkey != pubkey || event.kind != kind || !event.verify()) throw Nip46Exception("The signer returned an invalid signature")
        return event
    }

    fun close() = client.close()

    companion object {
        fun restore(clientKeys: Keys, s: BunkerSession, http: OkHttpClient, scope: CoroutineScope, onAuthUrl: (String) -> Unit) =
            Nip46Signer(Nip46Client(clientKeys, s.relays, http, scope, onAuthUrl).also { it.remotePubkey = s.remotePubkey }, s.userPubkey)
    }
}

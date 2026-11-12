package io.lawallet.wallet.core

import io.lawallet.wallet.core.auth.BunkerUri
import io.lawallet.wallet.core.auth.Nip46Client
import io.lawallet.wallet.core.auth.Nip46Exception
import io.lawallet.wallet.core.auth.Nip46Signer
import io.lawallet.wallet.core.auth.NostrConnectUri
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.Nip44
import io.lawallet.wallet.core.nostr.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class Nip46Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val bunkers = mutableListOf<FakeBunker>()

    @After fun tearDown() {
        scope.cancel()
        bunkers.forEach { runCatching { it.server.shutdown() } }
    }

    private val pk = "a".repeat(64)

    @Test fun parsesBunkerUri() {
        val u = BunkerUri.parse(" bunker://${pk.uppercase()}?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss://b.io&relay=wss://b.io&secret=s3cr3t ")
        assertEquals(pk, u.remotePubkey)
        assertEquals(listOf("wss://relay.nsec.app", "wss://b.io"), u.relays)
        assertEquals("s3cr3t", u.secret)
        assertNull(BunkerUri.parse("bunker://$pk?relay=wss://x").secret)
        assertThrows(IllegalArgumentException::class.java) { BunkerUri.parse("bunker://$pk") } // no relay
        assertThrows(IllegalArgumentException::class.java) { BunkerUri.parse("bunker://abc?relay=wss://x") }
        assertThrows(IllegalArgumentException::class.java) { BunkerUri.parse("nostrconnect://$pk?relay=wss://x") }
    }

    @Test fun nostrConnectUriRoundTrips() {
        val u = NostrConnectUri(pk, listOf("wss://relay.nsec.app", "wss://relay.damus.io"), "abc", Nip46Client.PERMS, "LaWallet Beta")
        val s = u.toString()
        assertTrue(s, s.startsWith("nostrconnect://$pk?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss%3A%2F%2Frelay.damus.io&secret=abc&perms=sign_event%3A27235&name=LaWallet%20Beta"))
        assertEquals(u, NostrConnectUri.parse(s))
    }

    @Test fun encodesRequestsAndDecodesResponses() {
        assertEquals("""{"id":"1","method":"sign_event","params":["{\"kind\":1}"]}""", Nip46Client.encodeRequest("1", "sign_event", listOf("""{"kind":1}""")))
        val ok = Nip46Client.decode("""{"id":"1","result":"ack","extra":1}""")
        assertEquals("ack", ok.result); assertNull(ok.error)
        val auth = Nip46Client.decode("""{"id":"2","result":"auth_url","error":"https://nsec.app/approve"}""")
        assertEquals("https://nsec.app/approve", auth.error)
    }

    @Test fun bunkerFlowConnectsAndSigns() = runBlocking {
        val b = FakeBunker(nip44 = true).start().also { bunkers += it }
        val uri = BunkerUri.parse(b.bunkerUri("sekret"))
        val client = Nip46Client(Keys.generate(), uri.relays, http, scope).also { it.remotePubkey = uri.remotePubkey }
        client.connect(uri.secret)
        val signer = Nip46Signer(client, client.getPublicKey())
        assertEquals(b.userKeys.pubkey, signer.pubkey)
        val ev = signer.sign(27235, "", listOf(listOf("u", "https://x/api/jwt"), listOf("method", "POST")))
        assertTrue(ev.verify())
        assertEquals(b.userKeys.pubkey, ev.pubkey)
        assertEquals(listOf("connect", "get_public_key", "sign_event"), b.methods)
        client.close()
    }

    @Test fun authUrlIsSurfacedThenAnswered() = runBlocking {
        val b = FakeBunker(nip44 = true, authUrlFirst = true).start().also { bunkers += it }
        val opened = CopyOnWriteArrayList<String>()
        val uri = BunkerUri.parse(b.bunkerUri(null))
        val client = Nip46Client(Keys.generate(), uri.relays, http, scope) { opened += it }.also { it.remotePubkey = uri.remotePubkey }
        client.connect(null)
        assertEquals(listOf("https://signer.example/approve"), opened)
        client.close()
    }

    @Test fun signerErrorsAreThrown() = runBlocking {
        val b = FakeBunker(nip44 = true, rejectSecret = true).start().also { bunkers += it }
        val uri = BunkerUri.parse(b.bunkerUri("wrong"))
        val client = Nip46Client(Keys.generate(), uri.relays, http, scope).also { it.remotePubkey = uri.remotePubkey }
        val e = runCatching { client.connect(uri.secret) }.exceptionOrNull()
        assertTrue(e is Nip46Exception && e.message == "invalid secret")
        client.close()
    }

    @Test fun nostrConnectFlowWithNip04Signer() = runBlocking {
        val b = FakeBunker(nip44 = false).start().also { bunkers += it }
        val keys = Keys.generate()
        val secret = Nip46Client.newSecret()
        val client = Nip46Client(keys, listOf(b.relayUrl), http, scope)
        val remote = async(start = CoroutineStart.UNDISPATCHED) { client.awaitConnect(secret, 10_000) }
        b.scanNostrConnect(NostrConnectUri.parse(NostrConnectUri(keys.pubkey, listOf(b.relayUrl), secret, Nip46Client.PERMS, "LaWallet").toString()))
        assertEquals(b.signerKeys.pubkey, remote.await())
        // Signer spoke NIP-04, so the client answers in NIP-04 from now on (the fake rejects anything else).
        assertEquals(b.userKeys.pubkey, client.getPublicKey())
        client.close()
    }

    @Test fun nostrConnectAcceptsLegacyAck() = runBlocking {
        val b = FakeBunker(nip44 = true).start().also { bunkers += it }
        val keys = Keys.generate()
        val client = Nip46Client(keys, listOf(b.relayUrl), http, scope)
        val remote = async(start = CoroutineStart.UNDISPATCHED) { client.awaitConnect("s", 10_000) }
        b.scanNostrConnect(NostrConnectUri(keys.pubkey, listOf(b.relayUrl), "ack", null, null))
        assertEquals(b.signerKeys.pubkey, remote.await())
        client.close()
    }

    /** A relay and a NIP-46 remote signer in one. Signer key != user key, as nsec.app does it. */
    class FakeBunker(private val nip44: Boolean, private val authUrlFirst: Boolean = false, private val rejectSecret: Boolean = false) : WebSocketListener() {
        val server = MockWebServer()
        val signerKeys = Keys.generate()
        val userKeys = Keys.generate()
        val methods = CopyOnWriteArrayList<String>()
        private val sockets = CopyOnWriteArrayList<WebSocket>()
        private val subs = ConcurrentHashMap<String, Pair<WebSocket, JsonObject>>()
        val relayUrl get() = server.url("/").toString().replace("http", "ws")

        fun bunkerUri(secret: String?) = "bunker://${signerKeys.pubkey}?relay=$relayUrl" + (secret?.let { "&secret=$it" } ?: "")

        fun start(): FakeBunker {
            repeat(8) { server.enqueue(MockResponse().withWebSocketUpgrade(this)) }
            server.start()
            return this
        }

        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(webSocket); webSocket.close(1000, null) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = NostrEvent.json.parseToJsonElement(text).jsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> subs[msg[1].jsonPrimitive.content] = webSocket to msg[2].jsonObject
                "CLOSE" -> subs.remove(msg[1].jsonPrimitive.content)
                "EVENT" -> {
                    val ev = NostrEvent.json.decodeFromJsonElement(NostrEvent.serializer(), msg[1])
                    webSocket.send("""["OK","${ev.id}",true,""]""")
                    if (ev.kind == 24133) handle(ev)
                }
            }
        }

        private fun handle(ev: NostrEvent) {
            check(ev.content.contains("?iv=") != nip44) { "client used the wrong encryption" }
            val plain = if (nip44) Nip44.decrypt(ev.content, signerKeys, ev.pubkey) else Nip04.decrypt(ev.content, signerKeys, ev.pubkey)
            val req = NostrEvent.json.parseToJsonElement(plain).jsonObject
            val id = req["id"]!!.jsonPrimitive.content
            val method = req["method"]!!.jsonPrimitive.content
            val params = req["params"]!!.jsonArray.map { it.jsonPrimitive.content }
            methods += method
            if (authUrlFirst && method == "connect") reply(ev.pubkey, buildJsonObject { put("id", id); put("result", "auth_url"); put("error", "https://signer.example/approve") })
            reply(ev.pubkey, buildJsonObject {
                put("id", id)
                when (method) {
                    "connect" -> if (rejectSecret) { put("result", ""); put("error", "invalid secret") } else put("result", params.getOrNull(1)?.ifEmpty { null } ?: "ack")
                    "get_public_key" -> put("result", userKeys.pubkey)
                    "sign_event" -> {
                        val u = NostrEvent.json.parseToJsonElement(params[0]).jsonObject
                        val tags = u["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
                        put("result", NostrEvent.sign(userKeys, u["kind"]!!.jsonPrimitive.int, u["content"]!!.jsonPrimitive.content, tags, u["created_at"]!!.jsonPrimitive.long).toJson())
                    }
                    else -> { put("result", ""); put("error", "unsupported") }
                }
            })
        }

        /** What a signer app does after scanning our QR: a `connect` reply carrying the secret. */
        fun scanNostrConnect(uri: NostrConnectUri) {
            Thread.sleep(500) // let the client's subscription land
            reply(uri.clientPubkey, buildJsonObject { put("id", "x"); put("result", uri.secret) })
        }

        private fun reply(to: String, body: JsonObject) {
            val content = if (nip44) Nip44.encrypt(body.toString(), signerKeys, to) else Nip04.encrypt(body.toString(), signerKeys, to)
            val ev = NostrEvent.sign(signerKeys, 24133, content, listOf(listOf("p", to)))
            subs.forEach { (subId, s) ->
                val (ws, f) = s
                if (f["#p"]?.jsonArray?.any { it.jsonPrimitive.content == to } == true)
                    ws.send(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(subId), NostrEvent.json.parseToJsonElement(ev.toJson()))).toString())
            }
        }
    }
}

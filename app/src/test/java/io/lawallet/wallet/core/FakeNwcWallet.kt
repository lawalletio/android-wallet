package io.lawallet.wallet.core

import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.Nip44
import io.lawallet.wallet.core.nostr.NostrEvent
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CopyOnWriteArrayList

/** A relay + NIP-47 wallet service in one, for tests. Holds a msat balance and pays/creates invoices. */
class FakeNwcWallet(private val nip44: Boolean) : WebSocketListener() {
    val server = MockWebServer()
    val walletKeys = Keys.generate()
    val clientKeys = Keys.generate()
    var balanceMsat = 21_000_000L
    val requests = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val subs = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    val connectionString: String
        get() = "nostr+walletconnect://${walletKeys.pubkey}?relay=${server.url("/").toString().replace("http", "ws")}&secret=${clientKeys.secretHex}&lud16=me%40lawallet.io"

    fun start(): FakeNwcWallet {
        repeat(8) { server.enqueue(MockResponse().withWebSocketUpgrade(this)) }
        server.start()
        return this
    }

    fun dropConnections() { sockets.forEach { it.close(1012, "restart") }; sockets.clear() }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(webSocket); webSocket.close(1000, null) }

    override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = NostrEvent.json.parseToJsonElement(text).jsonArray
        when (msg[0].jsonPrimitive.content) {
            "REQ" -> {
                val subId = msg[1].jsonPrimitive.content
                val filter = msg[2].jsonObject
                subs[subId] = filter
                if (filter["kinds"]!!.jsonArray.any { it.jsonPrimitive.int == 13194 }) {
                    val tags = if (nip44) listOf(listOf("encryption", "nip44_v2 nip04")) else emptyList()
                    send(webSocket, subId, NostrEvent.sign(walletKeys, 13194, "pay_invoice get_balance make_invoice lookup_invoice list_transactions notifications", tags))
                    webSocket.send("""["EOSE","$subId"]""")
                }
            }
            "CLOSE" -> subs.remove(msg[1].jsonPrimitive.content)
            "EVENT" -> {
                val ev = NostrEvent.json.decodeFromJsonElement(NostrEvent.serializer(), msg[1])
                webSocket.send("""["OK","${ev.id}",true,""]""")
                if (ev.kind == 23194) handleRequest(webSocket, ev)
            }
        }
    }

    private fun decrypt(content: String) = if (content.contains("?iv=")) Nip04.decrypt(content, walletKeys, clientKeys.pubkey) else Nip44.decrypt(content, walletKeys, clientKeys.pubkey)
    private fun encrypt(plain: String) = if (nip44) Nip44.encrypt(plain, walletKeys, clientKeys.pubkey) else Nip04.encrypt(plain, walletKeys, clientKeys.pubkey)

    private fun handleRequest(ws: WebSocket, ev: NostrEvent) {
        check(nip44 == (ev.tag("encryption") == "nip44_v2")) { "client used the wrong encryption" }
        val req = NostrEvent.json.parseToJsonElement(decrypt(ev.content)).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        val params = req["params"]!!.jsonObject
        requests += method
        val result: JsonObject? = when (method) {
            "get_balance" -> buildJsonObject { put("balance", balanceMsat) }
            "make_invoice" -> tx("incoming", params["amount"]!!.jsonPrimitive.long, "hash-make")
            "lookup_invoice" -> tx("incoming", 5000, params["payment_hash"]!!.jsonPrimitive.content, settled = true)
            "list_transactions" -> buildJsonObject { put("transactions", JsonArray(listOf(tx("incoming", 1000, "h1"), tx("outgoing", 2000, "h2")))) }
            "pay_invoice" -> if (balanceMsat < 1_000_000) null else {
                balanceMsat -= 1_000_000
                notify(tx("outgoing", 1_000_000, "paid-hash"), "payment_sent")
                buildJsonObject { put("preimage", "ff".repeat(32)); put("fees_paid", 1000) }
            }
            else -> null
        }
        val body = if (result != null) buildJsonObject { put("result_type", method); put("result", result) }
        else buildJsonObject { put("result_type", method); put("error", buildJsonObject { put("code", "INSUFFICIENT_BALANCE"); put("message", "Not enough sats") }) }
        val subId = subs.entries.first { (_, f) -> f["#e"]?.jsonArray?.any { it.jsonPrimitive.content == ev.id } == true }.key
        send(ws, subId, NostrEvent.sign(walletKeys, 23195, encrypt(body.toString()), listOf(listOf("p", clientKeys.pubkey), listOf("e", ev.id))))
    }

    fun notify(tx: JsonObject, type: String = "payment_received") {
        val kind = if (nip44) 23197 else 23196
        val body = buildJsonObject { put("notification_type", type); put("notification", tx) }.toString()
        val ev = NostrEvent.sign(walletKeys, kind, encrypt(body), listOf(listOf("p", clientKeys.pubkey)))
        subs.filter { (_, f) -> f["kinds"]!!.jsonArray.any { it.jsonPrimitive.int == kind } }.keys.forEach { id -> sockets.forEach { send(it, id, ev) } }
    }

    private fun send(ws: WebSocket, subId: String, ev: NostrEvent) {
        ws.send(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(subId), NostrEvent.json.parseToJsonElement(ev.toJson()))).toString())
    }

    companion object {
        fun tx(type: String, msat: Long, hash: String, settled: Boolean = false) = buildJsonObject {
            put("type", type); put("amount", msat); put("payment_hash", hash); put("invoice", "lnbc1fake"); put("description", "test")
            put("created_at", 1_700_000_000); if (settled) put("settled_at", 1_700_000_100)
        }
    }
}

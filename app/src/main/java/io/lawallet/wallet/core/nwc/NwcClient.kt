package io.lawallet.wallet.core.nwc

import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.Nip44
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.randomBytes
import io.lawallet.wallet.core.nostr.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * NIP-47 client — the Kotlin counterpart of @getalby/sdk `NWCClient` the web wallet uses.
 * Talks straight to the wallet's relay; the LaWallet server is never in the payment path.
 */
class NwcClient(val uri: NwcUri, http: OkHttpClient, scope: CoroutineScope) {
    // ponytail: first relay only (same as the web SDK default); fan out if wallets start listing several.
    val relay = Relay(uri.relays.first(), http, scope)
    private val keys = uri.secret
    val clientPubkey: String get() = keys.pubkey

    private enum class Enc { NIP04, NIP44 }
    private var encryption: Enc? = null
    private val infoLock = Mutex()
    private val conversationKey by lazy { Nip44.conversationKey(keys, uri.walletPubkey) }

    fun close() = relay.close()

    suspend fun getBalanceMsat(): Long = request<BalanceResult>("get_balance").balance

    suspend fun payInvoice(invoice: String, amountMsat: Long? = null): PayResult =
        request("pay_invoice", buildJsonObject {
            put("invoice", invoice)
            if (amountMsat != null) put("amount", amountMsat)
        })

    suspend fun makeInvoice(amountMsat: Long, description: String?): NwcTransaction =
        request("make_invoice", buildJsonObject {
            put("amount", amountMsat)
            if (!description.isNullOrBlank()) put("description", description)
        })

    suspend fun lookupInvoice(paymentHash: String): NwcTransaction =
        request("lookup_invoice", buildJsonObject { put("payment_hash", paymentHash) })

    suspend fun listTransactions(limit: Int? = null, from: Long? = null, until: Long? = null, type: String? = null): List<NwcTransaction> =
        request<TxList>("list_transactions", buildJsonObject {
            if (limit != null) put("limit", limit)
            if (from != null) put("from", from)
            if (until != null) put("until", until)
            if (type != null) put("type", type)
        }).transactions

    /** payment_received / payment_sent notifications (kinds 23196 NIP-04 and 23197 NIP-44). */
    fun notifications(): Flow<NwcNotification> {
        val subId = "ntf-" + randomBytes(6).toHex()
        return relay.messages
            .onStart {
                relay.subscribe(subId, buildJsonObject {
                    putJsonArray("kinds") { add(JsonPrimitive(23196)); add(JsonPrimitive(23197)) }
                    putJsonArray("authors") { add(JsonPrimitive(uri.walletPubkey)) }
                    putJsonArray("#p") { add(JsonPrimitive(keys.pubkey)) }
                })
            }
            .filterIsInstance<Relay.Msg.Event>()
            .mapNotNull { msg ->
                if (msg.subId != subId || msg.event.pubkey != uri.walletPubkey) return@mapNotNull null
                runCatching {
                    val plain = decrypt(msg.event.content, if (msg.event.kind == 23197) Enc.NIP44 else Enc.NIP04)
                    json.decodeFromString(NotificationEnvelope.serializer(), plain)
                        .takeIf { it.type == "payment_received" || it.type == "payment_sent" }
                        ?.let { NwcNotification(it.type, it.notification) }
                }.getOrNull()
            }
            .onCompletion { relay.unsubscribe(subId) }
    }

    private suspend inline fun <reified T> request(method: String, params: JsonObject = JsonObject(emptyMap())): T {
        val raw = requestRaw(method, params)
        return json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), raw)
    }

    suspend fun requestRaw(method: String, params: JsonObject, timeoutMs: Long = 60_000): JsonElement {
        val enc = encryption()
        val body = buildJsonObject { put("method", method); put("params", params) }.toString()
        val tags = buildList {
            add(listOf("p", uri.walletPubkey))
            if (enc == Enc.NIP44) add(listOf("encryption", "nip44_v2"))
        }
        val request = NostrEvent.sign(keys, 23194, encrypt(body, enc), tags)
        val subId = "rsp-" + request.id.take(12)
        return kotlinx.coroutines.coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                relay.messages.filterIsInstance<Relay.Msg.Event>()
                    .first { it.subId == subId && it.event.pubkey == uri.walletPubkey && it.event.tag("e") == request.id }
            }
            try {
                relay.subscribe(subId, buildJsonObject {
                    putJsonArray("kinds") { add(JsonPrimitive(23195)) }
                    putJsonArray("authors") { add(JsonPrimitive(uri.walletPubkey)) }
                    putJsonArray("#e") { add(JsonPrimitive(request.id)) }
                })
                relay.publish(request)
                val ev = withTimeoutOrNull(timeoutMs) { response.await() }?.event
                    ?: throw NwcException("TIMEOUT", "The wallet did not answer in time")
                val reply = json.decodeFromString(Response.serializer(), decrypt(ev.content, null))
                reply.error?.let { throw NwcException(it.code, it.message) }
                reply.result ?: JsonNull
            } finally {
                response.cancel()
                relay.unsubscribe(subId)
            }
        }
    }

    /** Reads the wallet's kind-13194 info event: nip44 when it advertises `nip44_v2`, else nip04. */
    private suspend fun encryption(): Enc = infoLock.withLock {
        encryption?.let { return it }
        val subId = "info-" + randomBytes(6).toHex()
        val result = kotlinx.coroutines.coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                relay.messages.first { (it is Relay.Msg.Event && it.subId == subId) || (it is Relay.Msg.Eose && it.subId == subId) }
            }
            relay.subscribe(subId, buildJsonObject {
                putJsonArray("kinds") { add(JsonPrimitive(13194)) }
                putJsonArray("authors") { add(JsonPrimitive(uri.walletPubkey)) }
                put("limit", 1)
            })
            val msg = withTimeoutOrNull(10_000) { first.await() }
            first.cancel()
            relay.unsubscribe(subId)
            val tag = (msg as? Relay.Msg.Event)?.event?.tag("encryption")
            if (tag != null && tag.split(' ').contains("nip44_v2")) Enc.NIP44 else Enc.NIP04
        }
        encryption = result
        result
    }

    private fun encrypt(plain: String, enc: Enc) =
        if (enc == Enc.NIP44) Nip44.encrypt(plain, conversationKey) else Nip04.encrypt(plain, keys, uri.walletPubkey)

    /** NIP-04 payloads carry `?iv=`; anything else is NIP-44. */
    private fun decrypt(content: String, hint: Enc?): String {
        val enc = hint ?: if (content.contains("?iv=")) Enc.NIP04 else Enc.NIP44
        return if (enc == Enc.NIP44) Nip44.decrypt(content, conversationKey) else Nip04.decrypt(content, keys, uri.walletPubkey)
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }

    @Serializable private data class Response(@SerialName("result_type") val resultType: String? = null, val error: NwcError? = null, val result: JsonElement? = null)
    @Serializable private data class NwcError(val code: String = "INTERNAL", val message: String = "Wallet error")
    @Serializable private data class BalanceResult(val balance: Long = 0)
    @Serializable private data class TxList(val transactions: List<NwcTransaction> = emptyList())
    @Serializable private data class NotificationEnvelope(@SerialName("notification_type") val type: String, val notification: NwcTransaction)
}

@Serializable
data class PayResult(val preimage: String = "", @SerialName("fees_paid") val feesPaid: Long? = null)

@Serializable
data class NwcTransaction(
    val type: String = "incoming",
    val state: String? = null,
    val invoice: String? = null,
    val description: String? = null,
    @SerialName("payment_hash") val paymentHash: String = "",
    val preimage: String? = null,
    val amount: Long = 0,
    @SerialName("fees_paid") val feesPaid: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("settled_at") val settledAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

data class NwcNotification(val type: String, val tx: NwcTransaction)

class NwcException(val code: String, message: String) : Exception(message)

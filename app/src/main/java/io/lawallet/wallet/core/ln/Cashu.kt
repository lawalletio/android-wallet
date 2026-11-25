package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.nostr.toHex
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.Number as CborNumber
import co.nstant.`in`.cbor.model.UnicodeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

class CashuException(message: String) : Exception(message)

data class CashuProof(val id: String, val amount: Long, val secret: String, val c: String, val witness: String? = null)

/** A NUT-00 ecash token (cashuA = V3 JSON, cashuB = V4 CBOR). Only single-mint tokens. */
data class CashuToken(val mint: String, val unit: String, val memo: String?, val proofs: List<CashuProof>) {
    val amount: Long get() = proofs.sumOf { it.amount }

    companion object {
        private val TOKEN = Regex("(?:^|[^A-Za-z0-9])(cashu[AB][A-Za-z0-9_\\-+/]+=*)")

        /** The token in [text] (bare, `cashu:` URI or a wallet link), or null when there is none / it doesn't decode. */
        fun parse(text: String): CashuToken? {
            val raw = TOKEN.find(text.trim())?.groupValues?.get(1) ?: return null
            return runCatching {
                val bytes = Base64.getUrlDecoder().decode(raw.substring(6).replace('+', '-').replace('/', '_').trimEnd('='))
                if (raw[5] == 'A') v3(bytes) else v4(bytes)
            }.getOrNull()?.takeIf { it.proofs.isNotEmpty() }
        }

        private fun v3(bytes: ByteArray): CashuToken {
            val json = lenientJson.parseToJsonElement(bytes.decodeToString()).jsonObject
            val entries = json["token"]!!.jsonArray.map { it.jsonObject }
            val mint = entries.first()["mint"]!!.jsonPrimitive.content
            require(entries.all { it["mint"]?.jsonPrimitive?.content == mint }) { "multi-mint token" }
            val proofs = entries.flatMap { e ->
                e["proofs"]!!.jsonArray.map { p ->
                    val o = p.jsonObject
                    CashuProof(o.s("id")!!, o["amount"]!!.jsonPrimitive.long, o.s("secret")!!, o.s("C")!!, o.s("witness"))
                }
            }
            return CashuToken(mint, json.s("unit") ?: "sat", json.s("memo"), proofs)
        }

        private fun v4(bytes: ByteArray): CashuToken {
            val dec = CborDecoder(ByteArrayInputStream(bytes)).apply { setMaxPreallocationSize(bytes.size) }
            val root = dec.decode().first() as CborMap
            val proofs = (root["t"] as CborArray).dataItems.flatMap { set ->
                set as CborMap
                val id = (set["i"] as ByteString).bytes.toHex()
                (set["p"] as CborArray).dataItems.map { p ->
                    p as CborMap
                    CashuProof(id, (p["a"] as CborNumber).value.toLong(), (p["s"] as UnicodeString).string, (p["c"] as ByteString).bytes.toHex(), (p["w"] as? UnicodeString)?.string)
                }
            }
            return CashuToken((root["m"] as UnicodeString).string, (root["u"] as? UnicodeString)?.string ?: "sat", (root["d"] as? UnicodeString)?.string, proofs)
        }

        private operator fun CborMap.get(key: String): DataItem? = get(UnicodeString(key))
        private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    }
}

/** Claims a token into the user's Lightning wallet: the token's mint pays (melts into) an invoice from that wallet (NUT-05). */
class CashuMint(http: OkHttpClient) {
    // A melt holds the request open while the mint routes the payment.
    private val http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    data class Claimed(val sats: Long, val paymentHash: String?, val paid: Boolean)

    /**
     * [makeInvoice] (sats → bolt11 + payment hash) is called until the mint's fee reserve fits in the token;
     * [onMelting] once the quote fits, right before the mint starts paying.
     * ponytail: no NUT-08 blank outputs, so an unused fee reserve stays at the mint (usually 1–2 sats).
     */
    suspend fun claim(token: CashuToken, onMelting: () -> Unit = {}, makeInvoice: suspend (Long) -> Pair<String, String?>): Claimed {
        if (token.unit != "sat") throw CashuException("Unsupported unit: ${token.unit}")
        val base = token.mint.trimEnd('/')
        val keysets = get("$base/v1/keysets")["keysets"]!!.jsonArray.map { it.jsonObject }
        // V4 tokens may carry a short keyset id: the mint wants the full one.
        val inputs = token.proofs.map { p -> p to keysets.firstOrNull { it.str("id")!!.startsWith(p.id) } }
        val fee = (inputs.sumOf { it.second?.get("input_fee_ppk")?.jsonPrimitive?.longOrNull ?: 0 } + 999) / 1000
        var amount = token.amount - fee
        repeat(4) {
            if (amount <= 0) throw CashuException("Token too small to cover the mint's fees")
            val (bolt11, hash) = makeInvoice(amount)
            val quote = post("$base/v1/melt/quote/bolt11", buildJsonObject { put("request", bolt11); put("unit", "sat") })
            val need = quote["amount"]!!.jsonPrimitive.long + (quote["fee_reserve"]?.jsonPrimitive?.longOrNull ?: 0) + fee
            if (need > token.amount) { amount -= need - token.amount; return@repeat }
            onMelting()
            val res = post("$base/v1/melt/bolt11", buildJsonObject {
                put("quote", quote.str("quote"))
                put("inputs", buildJsonArray {
                    inputs.forEach { (p, ks) ->
                        add(buildJsonObject {
                            put("id", ks?.str("id") ?: p.id); put("amount", p.amount); put("secret", p.secret); put("C", p.c)
                            p.witness?.let { put("witness", it) }
                        })
                    }
                })
            })
            val state = res.str("state") ?: if (res["paid"]?.jsonPrimitive?.contentOrNull == "true") "PAID" else "UNPAID"
            if (state == "UNPAID") throw CashuException("The mint could not pay the claim")
            return Claimed(amount, hash, state == "PAID")
        }
        throw CashuException("The mint's fees changed, try again")
    }

    private suspend fun get(url: String) = call(Request.Builder().url(url).build())
    private suspend fun post(url: String, body: JsonObject) =
        call(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build())

    private suspend fun call(req: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res ->
            val obj = runCatching { lenientJson.parseToJsonElement(res.body.string()).jsonObject }.getOrNull()
            // Mint errors are {detail, code}: "Token already spent", etc.
            if (!res.isSuccessful) throw CashuException(obj?.str("detail") ?: "Mint returned ${res.code}")
            obj ?: throw CashuException("Mint returned an invalid response")
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
}

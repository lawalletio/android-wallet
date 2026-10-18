package io.lawallet.wallet.core.ln

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class LnurlException(message: String, val code: String? = null, val status: Int? = null) : Exception(message)

data class WithdrawParams(
    val callback: String,
    val k1: String,
    val defaultDescription: String,
    val minSats: Long,
    val maxSats: Long,
    val host: String,
)

sealed interface LnurlResolved {
    data class Pay(val lnurlpUrl: String) : LnurlResolved
    data class Withdraw(val params: WithdrawParams) : LnurlResolved
}

/** LUD-06 payRequest metadata the send flow shows (name, avatar, comment budget, bounds). */
data class PayMetadata(
    val callback: String,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val commentAllowed: Int,
    val description: String?,
    val imageDataUri: String?,
    val identifier: String?,
    val allowsNostr: Boolean,
)

data class LnurlInvoice(val paymentRequest: String, val comment: String?, val verifyUrl: String?)

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull

/** Ports of lnurl-scan.ts / lnurl-invoice.ts / lnurl.ts. */
class Lnurl(private val http: OkHttpClient) {

    suspend fun resolve(input: String): LnurlResolved? {
        val url = toHttpUrl(input) ?: return null
        val json = try {
            http.getJsonObject(url)
        } catch (e: HttpStatusException) {
            throw LnurlException("LNURL endpoint returned ${e.status}", "httpStatus", e.status)
        } catch (e: IOException) {
            throw LnurlException("Could not reach the LNURL endpoint", "unreachable")
        } catch (e: IllegalStateException) {
            throw LnurlException("LNURL endpoint returned an invalid response", "invalidResponse")
        }
        if (json.str("status") == "ERROR") {
            throw json.str("reason")?.let { LnurlException(it) } ?: LnurlException("LNURL endpoint returned an error", "endpointError")
        }
        return when (json.str("tag")) {
            "withdrawRequest" -> LnurlResolved.Withdraw(parseWithdraw(json, url))
            "payRequest" -> LnurlResolved.Pay(url)
            else -> throw LnurlException("Unsupported LNURL type", "unsupported")
        }
    }

    suspend fun submitWithdraw(callback: String, k1: String, bolt11: String) {
        val base = callback.toHttpUrlOrNull() ?: throw LnurlException("Withdraw voucher has an invalid callback URL", "invalidCallback")
        val url = base.newBuilder().setQueryParameter("k1", k1).setQueryParameter("pr", bolt11).build().toString()
        val json = try {
            http.getJsonObject(url)
        } catch (e: HttpStatusException) {
            val reason = e.body?.str("reason")?.takeIf { it.isNotBlank() }
                ?: e.body?.get("error")?.let { runCatching { it.jsonObject.str("message") }.getOrNull() }?.takeIf { it.isNotBlank() }
            throw reason?.let { LnurlException(it) } ?: LnurlException("Withdraw service returned ${e.status}", "withdrawStatus", e.status)
        } catch (e: IOException) {
            throw LnurlException("Could not reach the withdraw service", "withdrawUnreachable")
        } catch (e: IllegalStateException) {
            return // non-JSON 2xx: treat as accepted, like the web (`res.json().catch(() => null)`)
        }
        if (json.str("status") == "ERROR") {
            throw json.str("reason")?.let { LnurlException(it) } ?: LnurlException("The withdraw request was rejected", "rejected")
        }
    }

    suspend fun payMetadata(lnurlpUrl: String): PayMetadata {
        val meta = try {
            http.getJsonObject(lnurlpUrl)
        } catch (e: HttpStatusException) {
            throw LnurlException("Recipient returned ${e.status}", "httpStatus", e.status)
        }
        if (meta.str("tag") != "payRequest") throw LnurlException("Recipient is not a Lightning address", "notPay")
        var description: String? = null
        var image: String? = null
        var identifier: String? = null
        runCatching {
            lenientJson.parseToJsonElement(meta.str("metadata") ?: "[]").jsonArray.forEach { entry ->
                val pair = entry.jsonArray
                val k = pair[0].jsonPrimitive.content
                val v = pair[1].jsonPrimitive.content
                when (k) {
                    "text/plain" -> description = v
                    "image/png;base64", "image/jpeg;base64" -> image = "data:${k.removeSuffix(";base64")};base64,$v"
                    "text/identifier", "text/email" -> identifier = v
                }
            }
        }
        return PayMetadata(
            callback = meta.str("callback") ?: throw LnurlException("Recipient is missing a callback", "invalidResponse"),
            minSendableMsat = meta.num("minSendable")?.toLong() ?: 1000,
            maxSendableMsat = meta.num("maxSendable")?.toLong() ?: Long.MAX_VALUE,
            commentAllowed = meta.num("commentAllowed")?.toInt() ?: 0,
            description = description,
            imageDataUri = image,
            identifier = identifier,
            allowsNostr = meta["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    suspend fun requestInvoice(lnurlpUrl: String, amountSats: Long, comment: String? = null): LnurlInvoice {
        if (amountSats <= 0) throw LnurlException("Enter an amount", "noAmount")
        val meta = payMetadata(lnurlpUrl)
        val msat = amountSats * 1000
        if (msat < meta.minSendableMsat || msat > meta.maxSendableMsat) {
            throw LnurlException(
                "Amount must be between ${ceil(meta.minSendableMsat / 1000.0).toLong()} and ${floor(meta.maxSendableMsat / 1000.0).toLong()} sats",
                "outOfRange",
            )
        }
        val sentComment = commentWithinBudget(comment, meta.commentAllowed)
        val base = meta.callback.toHttpUrl().newBuilder().setQueryParameter("amount", msat.toString())
        return try {
            fetchInvoice(base.apply { if (sentComment != null) setQueryParameter("comment", sentComment) }.build().toString(), sentComment)
        } catch (e: Exception) {
            if (sentComment == null) throw e
            fetchInvoice(base.removeAllQueryParameters("comment").build().toString(), null)
        }
    }

    private suspend fun fetchInvoice(url: String, comment: String?): LnurlInvoice {
        val json = try {
            http.getJsonObject(url)
        } catch (e: HttpStatusException) {
            throw LnurlException("Recipient callback returned ${e.status}", "httpStatus", e.status)
        }
        val pr = json.str("pr")
        if (json.str("status") == "ERROR" || pr.isNullOrBlank()) {
            throw LnurlException(json.str("reason") ?: "Recipient refused the invoice request", "refused")
        }
        return LnurlInvoice(pr, comment, json.str("verify"))
    }

    /** LUD-21 single check: `{settled, preimage}`. */
    suspend fun verifyOnce(verifyUrl: String): Pair<Boolean, String?> {
        val json = http.getJsonObject(verifyUrl)
        return (json["settled"]?.jsonPrimitive?.booleanOrNull == true) to json.str("preimage")
    }

    suspend fun pollVerify(verifyUrl: String, intervalMs: Long = 3000, timeoutMs: Long = 300_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching { verifyOnce(verifyUrl) }.getOrNull()?.let { (settled, preimage) -> if (settled) return preimage ?: "" }
            delay(intervalMs)
        }
        return null
    }

    companion object {
        private val PSEUDO = Regex("^(lnurlw|lnurlp|lnurlc|keyauth)://")
        private val ONION = Regex("^[^/]*\\.onion(?::\\d+)?(?:/|$)", RegexOption.IGNORE_CASE)

        private fun stripLightning(s: String): String {
            val t = s.trim()
            return if (t.lowercase().startsWith("lightning:")) t.substring(10).trim() else t
        }

        fun toHttpUrl(input: String): String? {
            val text = stripLightning(input)
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            PSEUDO.find(lower)?.let { m ->
                val rest = text.substring(m.value.length)
                return (if (ONION.containsMatchIn(rest)) "http://" else "https://") + rest
            }
            if (lower.startsWith("lnurl1")) return DestinationParser.decodeLnurl(text)
            if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
            return null
        }

        fun looksLikeLnurl(input: String): Boolean {
            val text = stripLightning(input)
            val lower = text.lowercase()
            if (lower.startsWith("lnurl1")) return true
            if (PSEUDO.containsMatchIn(lower)) return true
            if (Regex("^https?://").containsMatchIn(lower)) {
                val u = text.toHttpUrlOrNull() ?: return false
                val tag = u.queryParameter("tag")
                return tag == "withdrawRequest" || tag == "payRequest" || u.queryParameterNames.contains("k1")
            }
            return false
        }

        fun commentWithinBudget(comment: String?, commentAllowed: Int): String? {
            val t = comment?.trim().orEmpty()
            if (t.isEmpty() || commentAllowed <= 0) return null
            return t.take(commentAllowed).ifEmpty { null }
        }

        fun parseWithdraw(json: JsonObject, endpointUrl: String): WithdrawParams {
            val callback = json.str("callback").orEmpty()
            val k1 = json.str("k1").orEmpty()
            if (callback.isEmpty() || k1.isEmpty()) throw LnurlException("Withdraw voucher is missing required fields", "missingFields")
            val minMsat = json.num("minWithdrawable") ?: 0.0
            val maxMsat = json.num("maxWithdrawable") ?: 0.0
            if (maxMsat <= 0) throw LnurlException("Withdraw voucher has no withdrawable amount", "noAmount")
            val minSats = ceil(max(0.0, minMsat) / 1000).toLong()
            val maxSats = floor(maxMsat / 1000).toLong()
            if (maxSats < 1) throw LnurlException("Withdrawable amount is below 1 sat", "belowOneSat")
            return WithdrawParams(
                callback, k1, json.str("defaultDescription").orEmpty(),
                min(minSats, maxSats), maxSats,
                endpointUrl.toHttpUrlOrNull()?.let { if (it.port == okhttp3.HttpUrl.defaultPort(it.scheme)) it.host else "${it.host}:${it.port}" }.orEmpty(),
            )
        }
    }
}

package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.diag.Trail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

class HttpStatusException(val status: Int, val body: JsonObject?) : Exception("HTTP $status")

/** GET a JSON object (LNURL services, Yadio). Throws [HttpStatusException] on non-2xx with the parsed body. */
suspend fun OkHttpClient.getJsonObject(url: String): JsonObject = withContext(Dispatchers.IO) {
    // Trail lines carry no host or URL: which LNURL service the user pays is theirs.
    val call = try { newCall(Request.Builder().url(url).header("accept", "application/json").build()).execute() } catch (e: IOException) {
        Trail.add("http", "GET failed ${e.javaClass.simpleName}", error = true)
        throw e
    }
    call.use { res ->
        val text = res.body.string()
        val obj = runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
        if (!res.isSuccessful) Trail.add("http", "GET -> ${res.code}", error = true)
        if (!res.isSuccessful) throw HttpStatusException(res.code, obj)
        obj ?: throw IllegalStateException("invalid JSON response")
    }
}

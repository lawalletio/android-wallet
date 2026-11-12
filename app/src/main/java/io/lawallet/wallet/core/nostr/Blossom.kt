package io.lawallet.wallet.core.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.Base64

/** Blossom (BUD-02) uploads: `PUT <server>/upload` with a kind-24242 authorization signed by the user. */
object Blossom {
    /** The web app's defaults, for instances that don't set `blossom_servers`. */
    val DEFAULT_SERVERS = listOf("https://blossom.primal.net", "https://cdn.satellite.earth", "https://cdn.nostrcheck.me", "https://blossom.nostr.build", "https://blossom.band")

    /** Like the web: unset → defaults; set but empty/malformed → none (the operator opted out). */
    fun servers(setting: String?): List<String> = if (setting == null) DEFAULT_SERVERS else runCatching {
        NostrEvent.json.parseToJsonElement(setting).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    fun authTags(sha256Hex: String, now: Long = NostrEvent.nowSeconds()) =
        listOf(listOf("t", "upload"), listOf("x", sha256Hex), listOf("expiration", (now + 300).toString()))

    fun authHeader(event: NostrEvent) = "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toByteArray())

    /**
     * Uploads [bytes] to the first of [servers] that takes them and returns the blob's URL.
     * [onProgress] gets 0..1 as bytes go out; one signature covers every server tried.
     */
    suspend fun upload(http: OkHttpClient, signer: Signer, servers: List<String>, bytes: ByteArray, mime: String, onProgress: (Float) -> Unit): String {
        require(servers.isNotEmpty()) { "No media servers configured" }
        val hash = sha256(bytes).toHex()
        val auth = authHeader(signer.sign(24242, "Upload profile image", authTags(hash)))
        var last: Exception? = null
        for (server in servers) {
            try {
                onProgress(0f)
                val req = Request.Builder().url(server.trimEnd('/') + "/upload").header("Authorization", auth)
                    .put(ProgressBody(bytes, mime.toMediaType(), onProgress)).build()
                return withContext(Dispatchers.IO) {
                    http.newCall(req).execute().use { res ->
                        val body = res.body.string()
                        if (!res.isSuccessful) throw IllegalStateException("${res.code} ${res.header("X-Reason") ?: body.take(120)}")
                        NostrEvent.json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no url in response")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last!!
    }

    private class ProgressBody(private val bytes: ByteArray, private val type: MediaType, private val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0
            while (sent < bytes.size) {
                val n = minOf(16 * 1024, bytes.size - sent)
                sink.write(bytes, sent, n)
                sink.flush()
                sent += n
                onProgress(sent.toFloat() / bytes.size)
            }
        }
    }
}

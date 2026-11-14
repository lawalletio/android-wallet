package io.lawallet.wallet.core.nwc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** LNCurl providers: POST to the origin and it answers with a fresh `nostr+walletconnect://` wallet (lib/lncurl.ts). */
object Lncurl {
    /** The LaWallet server's default; picking it creates the wallet through the server (keeps the LNCurl tag and revive). */
    const val DEFAULT = "https://lncurl.lol"
    val PRESETS = listOf("lncurl.lol" to DEFAULT, "Masize" to "https://lncurl.masize.com")

    private val NWC = Regex("""(nostr\+walletconnect|nostrwalletconnect)://[^\s"'`<>]+""", RegexOption.IGNORE_CASE)

    /** The first NWC URI in a reply, whether it's bare text, a JSON field or prose. */
    fun parseNwcUri(body: String): String? = NWC.find(body)?.value

    /** A typed provider as a bare https origin ("lncurl.example.com" works too), or null when it isn't one. */
    fun normalize(input: String): String? {
        val t = input.trim().let { if ("://" in it) it else "https://$it" }
        val url = t.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host.contains('.') } ?: return null
        return t.trimEnd('/').takeIf { url.encodedPath == "/" } ?: return null
    }

    fun host(origin: String): String = origin.toHttpUrlOrNull()?.host ?: origin

    /** Mints a wallet at [origin] and returns its NWC connection string. */
    suspend fun mint(http: OkHttpClient, origin: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(origin).post(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) throw IOException("LNCurl server returned ${res.code}")
            parseNwcUri(body) ?: throw IOException("LNCurl reply has no wallet connection")
        }
    }
}

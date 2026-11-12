package io.lawallet.wallet.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntRect
import io.lawallet.wallet.core.nostr.Blossom
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Kind0
import io.lawallet.wallet.core.nostr.LocalSigner
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.ui.components.cropBounds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditTest {
    private fun kind0(json: String) = Kind0(NostrEvent.json.parseToJsonElement(json).jsonObject)

    @Test fun mergeKeepsUnknownKeysAndDropsBlanks() {
        val p = kind0("""{"name":"ana","displayName":"Old","about":"hi","lud16":"ana@x.io","website":"https://a.b"}""")
        assertEquals("Old", p.displayName)
        val out = p.merged(mapOf("display_name" to " Ana ", "about" to "", "name" to "ana"))
        assertEquals(JsonPrimitive("Ana"), out["display_name"])
        assertFalse("camelCase twin dropped", "displayName" in out)
        assertFalse("blank removes", "about" in out)
        assertEquals(JsonPrimitive("ana@x.io"), out["lud16"])
        assertEquals(JsonPrimitive("https://a.b"), out["website"])
    }

    @Test fun blossomServersFollowTheWebRules() {
        assertEquals(Blossom.DEFAULT_SERVERS, Blossom.servers(null))
        assertEquals(emptyList<String>(), Blossom.servers("[]"))
        assertEquals(emptyList<String>(), Blossom.servers("nope"))
        assertEquals(listOf("https://b.example"), Blossom.servers("""[" https://b.example ", ""]"""))
        val tags = Blossom.authTags("ab", now = 100)
        assertTrue(listOf("t", "upload") in tags && listOf("x", "ab") in tags && listOf("expiration", "400") in tags)
    }

    @Test fun uploadSignsBlobAndFallsBackToTheNextServer() = runBlocking {
        val down = MockWebServer().apply { enqueue(MockResponse().setResponseCode(500)) }
        val up = MockWebServer().apply { enqueue(MockResponse().setBody("""{"url":"https://cdn.example/abc.jpg","sha256":"abc"}""")) }
        val keys = Keys.generate()
        val bytes = ByteArray(40_000) { it.toByte() }
        var last = 0f
        val url = Blossom.upload(OkHttpClient(), LocalSigner(keys), listOf(down.url("/").toString(), up.url("/").toString()), bytes, "image/jpeg") { last = it }
        assertEquals("https://cdn.example/abc.jpg", url)
        assertEquals(1f, last)
        val req = up.takeRequest()
        assertEquals("PUT", req.method); assertEquals("/upload", req.path)
        val auth = NostrEvent.json.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(req.getHeader("Authorization")!!.removePrefix("Nostr "))))
        assertTrue(auth.verify())
        assertEquals(24242, auth.kind)
        assertEquals(sha256(bytes).toHex(), auth.tag("x"))
        assertEquals(keys.pubkey, auth.pubkey)
        down.shutdown(); up.shutdown()
    }

    @Test fun cropBoundsMapFrameToBitmapPixels() {
        // 2000x1000 photo, square 500px frame: covering scale is 0.5, so the frame sees the centre 1000x1000.
        assertEquals(IntRect(500, 0, 1500, 1000), cropBounds(2000, 1000, Size(500f, 500f), 0.5f, Offset.Zero))
        // Dragging the photo 100px right shows 200 bitmap px further left.
        assertEquals(IntRect(300, 0, 1300, 1000), cropBounds(2000, 1000, Size(500f, 500f), 0.5f, Offset(100f, 0f)))
        // 2x zoom halves the window.
        assertEquals(IntRect(750, 250, 1250, 750), cropBounds(2000, 1000, Size(500f, 500f), 1f, Offset.Zero))
    }
}

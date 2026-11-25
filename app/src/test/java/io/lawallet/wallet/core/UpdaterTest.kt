package io.lawallet.wallet.core

import io.lawallet.wallet.app.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Robolectric for checkIfDue (Context, SystemClock); the rest is pure. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UpdaterTest {
    @Test
    fun versions() {
        assertTrue(Updater.isNewer("1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.0", "0.0.0-abc1234")) // CI non-tag builds
    }

    private val assets = listOf(
        "lawallet-1.0.1-arm64-v8a.apk", "lawallet-1.0.1-armeabi-v7a.apk",
        "lawallet-1.0.1-universal.apk", "lawallet-1.0.1-x86_64.apk", "SHA256SUMS.txt",
    )

    @Test
    fun picksTheDevicesAbi() {
        assertEquals("lawallet-1.0.1-arm64-v8a.apk", Updater.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("lawallet-1.0.1-armeabi-v7a.apk", Updater.pickApk(assets, listOf("armeabi-v7a", "armeabi")))
        assertEquals("lawallet-1.0.1-universal.apk", Updater.pickApk(assets, listOf("riscv64")))
        assertNull(Updater.pickApk(listOf("SHA256SUMS.txt"), listOf("arm64-v8a")))
    }

    @Test
    fun readsSha256sums() {
        val sums = "D91373C5  lawallet-1.0.1-arm64-v8a.apk\n8dfc3bbe *lawallet-1.0.1-armeabi-v7a.apk\n"
        assertEquals("d91373c5", Updater.expectedSha256(sums, "lawallet-1.0.1-arm64-v8a.apk"))
        assertEquals("8dfc3bbe", Updater.expectedSha256(sums, "lawallet-1.0.1-armeabi-v7a.apk"))
        assertNull(Updater.expectedSha256(sums, "lawallet-1.0.1-x86_64.apk"))
    }

    // regression: 52fa08a fix(updates): detect new releases on every app open
    @Test
    fun regression_52fa08a_checksEveryFewMinutesWithAConditionalRequest() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            // The feed URL is fixed: every request goes to the mock server.
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { ch -> ch.proceed(ch.request().newBuilder().url(server.url("/feed")).build()) }.build()
            val updater = Updater(androidx.test.core.app.ApplicationProvider.getApplicationContext(), http)
            fun release(tag: String, etag: String) = okhttp3.mockwebserver.MockResponse().setHeader("etag", etag).setBody("""{"tag_name":"$tag","assets":[]}""")
            fun version() = (updater.state.value as Updater.State.Available).version

            server.enqueue(release("v99.0.0", "e1"))
            updater.checkIfDue()
            assertEquals("99.0.0", version())
            updater.checkIfDue() // a minute later: throttled
            assertEquals(1, server.requestCount)
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)

            ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
            updater.checkIfDue()
            assertEquals("e1", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.getHeader("if-none-match")) // free on GitHub's rate limit
            assertEquals("99.0.0", version())

            ShadowSystemClock.advanceBy(Duration.ofMinutes(6)) // well within the old hourly throttle
            server.enqueue(release("v99.1.0", "e2"))
            updater.checkIfDue()
            assertEquals("99.1.0", version())
        } finally { server.shutdown() }
    }
}

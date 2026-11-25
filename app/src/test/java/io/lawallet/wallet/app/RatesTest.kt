package io.lawallet.wallet.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.lawallet.wallet.core.fx.YadioRates
import io.lawallet.wallet.core.ln.HttpStatusException
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Yadio decoding, and the 60 s refresh loop on virtual time (the fetch itself runs on a real IO thread). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RatesTest {
    private val server = MockWebServer().apply { start() }
    // Yadio's URL is fixed: every request goes to the mock server instead, path kept.
    private val http = OkHttpClient.Builder().addInterceptor { ch -> ch.proceed(ch.request().newBuilder().url(server.url(ch.request().url.encodedPath)).build()) }.build()
    private val yadio = YadioRates(http)

    @After fun tearDown() = server.shutdown()

    private fun rates(json: String) = MockResponse().setBody("""{"BTC":$json,"base":"BTC","timestamp":1}""")

    @Test fun fetchKeepsNumericRatesOnly() = runBlocking {
        server.enqueue(rates("""{"USD":100000.5,"ARS":120000000,"XYZ":"n/a","EUR":null}"""))
        assertEquals(mapOf("USD" to 100000.5, "ARS" to 1.2e8), yadio.fetch())
        assertEquals("/exrates/BTC", server.takeRequest().path)
    }

    @Test fun fetchSurfacesHttpErrors() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))
        assertEquals(429, assertThrows(HttpStatusException::class.java) { runBlocking { yadio.fetch() } }.status)
    }

    @Test fun refreshesEveryMinuteOnlyWhileActiveAndKeepsTheLastGoodRates() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext()).apply { ratesJson = """{"USD":1.0}""" }
        val scope = TestScope()
        val repo = RatesRepository(yadio, prefs, scope)
        fun settle(until: () -> Boolean) { val end = System.currentTimeMillis() + 5_000; while (!until() && System.currentTimeMillis() < end) { scope.testScheduler.runCurrent(); Thread.sleep(10) } }
        try {
            assertEquals(mapOf("USD" to 1.0), repo.rates.value) // the persisted value, before any fetch
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(5 * 60_000L)
            assertEquals(0, server.requestCount) // in background: no requests

            server.enqueue(rates("""{"USD":2.0}"""))
            repo.active.value = true
            scope.testScheduler.advanceTimeBy(60_000L); scope.testScheduler.runCurrent()
            settle { repo.rates.value == mapOf("USD" to 2.0) }
            assertEquals(mapOf("USD" to 2.0), repo.rates.value)
            assertEquals("""{"USD":2.0}""", prefs.ratesJson)

            server.enqueue(MockResponse().setResponseCode(500))
            scope.testScheduler.advanceTimeBy(60_000L); scope.testScheduler.runCurrent()
            settle { server.requestCount == 2 }
            Thread.sleep(100); scope.testScheduler.runCurrent()
            assertEquals(2, server.requestCount) // one per minute
            assertEquals(mapOf("USD" to 2.0), repo.rates.value) // a failed refresh keeps what we had
        } finally { scope.cancel() }
    }
}

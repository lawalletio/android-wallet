package io.lawallet.wallet.core

import io.lawallet.wallet.core.diag.Scrubber
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringWriter

/** Nothing a wallet must keep on the device survives [Scrubber], whichever field it hides in. */
class ScrubberTest {
    private val secrets = listOf(
        "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
        "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg",
        "nostr+walletconnect://b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4?relay=wss%3A%2F%2Frelay.example&secret=71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c",
        "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu",
        "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS",
        "4e2f1c5d8b9a7e6f3c2d1b0a9e8f7d6c5b4a3e2f1d0c9b8a7e6f5d4c3b2a1e0f", // preimage / payment hash
        "alice@lawallet.ar",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl",
        "https://pay.example/lnurlp/callback?k1=abcdef&pr=whatever",
        "21000 sats",
    )
    private val all = secrets.joinToString(" ")

    @Test
    fun nothingSensitiveSurvives() {
        val event = SentryEvent(RuntimeException(all)).apply {
            exceptions = listOf(SentryException().apply { type = "JsonDecodingException"; value = "Unexpected JSON token: $all" })
            message = Message().apply { formatted = "payment failed $all"; message = all }
            user = User().apply { id = "installation-id"; username = all }
            request = Request().apply { url = secrets[8] }
            serverName = "pixel"
            threads = listOf(SentryThread().apply { name = "OkHttp ${secrets[8]}" }, SentryThread().apply { name = "worker ${secrets[2]}" })
            setTag("route", "activity/${secrets[5]}")
            setTag("state", all)
            setExtra("invoice", secrets[3])
            contexts.set("wallet", mapOf("nwc" to secrets[2]))
            contexts.setDevice(io.sentry.protocol.Device().apply { id = "installation-id"; model = "Pixel 9" })
            transaction = "activity/${secrets[5]}"
            breadcrumbs = listOf(
                Breadcrumb().apply { category = "navigation"; message = "activity/${secrets[5]}" },
                Breadcrumb().apply { category = "http"; setData("url", secrets[8]) },
                Breadcrumb().apply { category = "relay"; message = "dropped $all" },
            )
        }

        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(Scrubber.event(event), it) }.toString()

        secrets.forEach { assertFalse("leaked: $it\n$json", json.contains(it, ignoreCase = true)) }
        assertFalse(json.contains("installation-id"))
        assertFalse(json.contains("21000"))
        assertNull(event.message) // not one of ours
        assertEquals("Pixel 9", event.contexts.device?.model)
        assertEquals("OkHttp", event.threads!![0].name)
        assertEquals(listOf("relay"), event.breadcrumbs!!.map { it.category })
    }

    @Test
    fun ownMessagesAndAllowedTagsStay() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "exit.low_memory" }
            setTag("reason", "low_memory")
            setTag("pss_mb", "212")
        }
        Scrubber.event(event)
        assertEquals("exit.low_memory", event.message?.formatted)
        assertEquals("212", event.getTag("pss_mb"))
    }
}

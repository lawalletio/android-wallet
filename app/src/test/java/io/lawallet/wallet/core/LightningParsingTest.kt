package io.lawallet.wallet.core

import io.lawallet.wallet.core.ln.ActivationUrl
import io.lawallet.wallet.core.ln.Bolt11
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.ln.DestinationException
import io.lawallet.wallet.core.ln.DestinationParser
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.LnurlException
import io.lawallet.wallet.core.ln.NdefRecordData
import io.lawallet.wallet.core.ln.NdefUrl
import io.lawallet.wallet.core.nostr.Bech32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LightningParsingTest {
    // BOLT-11 spec examples; expectations cross-checked with light-bolt11-decoder (what the web uses).
    private val coffee = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    private val donation = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"

    @Test fun bolt11() {
        val c = Bolt11.decode(coffee)
        assertEquals(250_000_000L, c.amountMsat)
        assertEquals(250_000L, c.amountSats)
        assertEquals("1 cup coffee", c.description)
        assertEquals("0001020304050607080900010203040506070809000102030405060708090102", c.paymentHash)
        assertEquals(1496314658L, c.timestamp)
        assertEquals(60L, c.expirySeconds)
        val d = Bolt11.decode("lightning:$donation")
        assertNull(d.amountMsat)
        assertEquals(3600L, d.expirySeconds)
        assertEquals("Please consider supporting this project", d.description)
        assertThrows(Exception::class.java) { Bolt11.decode(coffee.dropLast(1) + "q") }
    }

    @Test fun destinations() {
        val inv = DestinationParser.parse("LIGHTNING:$coffee") as Destination.Invoice
        assertEquals(250_000L, inv.amountSats)
        assertEquals((1496314658L + 60) * 1000, inv.expiresAtMs)

        val addr = DestinationParser.parse(" satoshi@lawallet.io ") as Destination.LnurlPay
        assertEquals("https://lawallet.io/.well-known/lnurlp/satoshi", addr.lnurlpUrl)
        assertEquals("satoshi", addr.username)

        val lnurl = Bech32.encodeBytes("lnurl", "https://service.com/api?q=3fc3645b439ce8e7".toByteArray())
        assertEquals("https://service.com/api?q=3fc3645b439ce8e7", (DestinationParser.parse(lnurl.uppercase()) as Destination.LnurlPay).lnurlpUrl)

        assertTrue(DestinationParser.parse("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6") is Destination.Npub)
        assertEquals("empty", assertThrows(DestinationException::class.java) { DestinationParser.parse("  ") }.code)
        assertEquals("unsupported", assertThrows(DestinationException::class.java) { DestinationParser.parse("hello") }.code)
        assertEquals("invalidInvoice", assertThrows(DestinationException::class.java) { DestinationParser.parse("lnbc1garbage") }.code)
    }

    @Test fun lnurlForms() {
        assertEquals("https://card.io/w?p=1", Lnurl.toHttpUrl("lnurlw://card.io/w?p=1"))
        assertEquals("http://abc.onion/w", Lnurl.toHttpUrl("lightning:LNURLW://abc.onion/w"))
        assertEquals("https://a.b/x", Lnurl.toHttpUrl("https://a.b/x"))
        assertNull(Lnurl.toHttpUrl("satoshi@lawallet.io"))
        assertTrue(Lnurl.looksLikeLnurl("https://x.io/api/cards/1/scan?tag=withdrawRequest"))
        assertTrue(Lnurl.looksLikeLnurl("https://x.io/cb?k1=abc"))
        assertTrue(Lnurl.looksLikeLnurl("lnurlp://x.io/p"))
        assertFalse(Lnurl.looksLikeLnurl("https://x.io/blog"))
        assertFalse(Lnurl.looksLikeLnurl("satoshi@lawallet.io"))
        assertEquals("hel", Lnurl.commentWithinBudget(" hello ", 3))
        assertNull(Lnurl.commentWithinBudget("hello", 0))
    }

    @Test fun withdrawParamsClampToWholeSats() {
        val p = Lnurl.parseWithdraw(json("""{"tag":"withdrawRequest","callback":"https://x.io/cb","k1":"k","minWithdrawable":1500,"maxWithdrawable":10999,"defaultDescription":"gift"}"""), "https://x.io:8443/w")
        assertEquals(2L, p.minSats)
        assertEquals(10L, p.maxSats)
        assertEquals("x.io:8443", p.host)
        assertEquals("gift", p.defaultDescription)
        assertEquals("noAmount", assertThrows(LnurlException::class.java) { Lnurl.parseWithdraw(json("""{"callback":"c","k1":"k","maxWithdrawable":0}"""), "") }.code)
        assertEquals("belowOneSat", assertThrows(LnurlException::class.java) { Lnurl.parseWithdraw(json("""{"callback":"c","k1":"k","maxWithdrawable":999}"""), "") }.code)
        assertEquals("missingFields", assertThrows(LnurlException::class.java) { Lnurl.parseWithdraw(json("""{"maxWithdrawable":5000}"""), "") }.code)
    }

    @Test fun activationUrls() {
        assertEquals(ActivationUrl("abc123", "beta.lawallet.io"), ActivationUrl.parse("https://beta.lawallet.io/wallet/activate/abc123/"))
        assertEquals("10.0.2.2:3052", ActivationUrl.parse("http://10.0.2.2:3052/wallet/activate/x_y-z")?.host)
        assertNull(ActivationUrl.parse("https://beta.lawallet.io/wallet/activate"))
        assertNull(ActivationUrl.parse("https://beta.lawallet.io/wallet/activate/a/b"))
        assertNull(ActivationUrl.parse("lnurlw://x/wallet/activate/a"))
    }

    @Test fun ndefUrls() {
        val uriRecord = NdefRecordData(1, "U".toByteArray(), byteArrayOf(4) + "card.lawallet.io/api/cards/1/scan?p=A&c=B".toByteArray())
        assertEquals("https://card.lawallet.io/api/cards/1/scan?p=A&c=B", NdefUrl.recordToUrl(uriRecord))
        val text = NdefRecordData(1, "T".toByteArray(), byteArrayOf(2) + "en".toByteArray() + "lnurlw://card.io/w?p=1".toByteArray())
        val aar = NdefRecordData(4, "android.com:pkg".toByteArray(), "io.lawallet".toByteArray())
        assertEquals("lnurlw://card.io/w?p=1", NdefUrl.firstBoltcardUrl(listOf(uriRecord, aar, text)))
        assertNull(NdefUrl.firstBoltcardUrl(listOf(aar)))
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
}

package io.lawallet.wallet.core

import io.lawallet.wallet.app.Session
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.ln.BoltcardPay
import io.lawallet.wallet.core.ln.BoltcardPayException
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.LnurlException
import io.lawallet.wallet.core.ln.LnurlResolved
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.core.ln.ScanRouter
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nwc.NwcTransaction
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** LNURL pay/withdraw, Bolt card charging and scan routing against a scripted LNURL service. */
class LnurlFlowsTest {
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private var routes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val lnurl = Lnurl(OkHttpClient())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { calls += request; return routes(request) }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()
    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)

    private fun payService(commentAllowed: Int, rejectComments: Boolean) {
        routes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/p" -> json("""{"tag":"payRequest","callback":"${url("/cb")}","minSendable":1000,"maxSendable":100000000,"commentAllowed":$commentAllowed,"metadata":"[[\"text/plain\",\"Coffee\"],[\"text/identifier\",\"bob@x.io\"]]"}""")
                "/cb" -> if (rejectComments && r.requestUrl!!.queryParameter("comment") != null) json("""{"status":"ERROR","reason":"no comments"}""")
                else json("""{"pr":"lnbc1fakeinvoice","verify":"${url("/v")}"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun payMetadataAndInvoiceWithinCommentBudget() = runBlocking {
        payService(commentAllowed = 5, rejectComments = false)
        val meta = lnurl.payMetadata(url("/p"))
        assertEquals("Coffee", meta.description)
        assertEquals("bob@x.io", meta.identifier)
        val inv = lnurl.requestInvoice(url("/p"), 21, "hello world")
        assertEquals("lnbc1fakeinvoice", inv.paymentRequest)
        assertEquals("hello", inv.comment)
        val cb = calls.last().requestUrl!!
        assertEquals("21000", cb.queryParameter("amount"))
        assertEquals("hello", cb.queryParameter("comment"))
    }

    @Test fun retriesWithoutTheCommentWhenTheRecipientRejectsIt() = runBlocking {
        payService(commentAllowed = 100, rejectComments = true)
        val inv = lnurl.requestInvoice(url("/p"), 21, "thanks")
        assertNull(inv.comment)
        assertNull(calls.last().requestUrl!!.queryParameter("comment"))
    }

    @Test fun rejectsAmountsOutsideTheRecipientsBounds() {
        payService(commentAllowed = 0, rejectComments = false)
        val e = assertThrows(LnurlException::class.java) { runBlocking { lnurl.requestInvoice(url("/p"), 200_000, null) } }
        assertEquals("outOfRange", e.code)
    }

    @Test fun resolvesPayWithdrawAndErrors() = runBlocking {
        routes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/w" -> json("""{"tag":"withdrawRequest","callback":"${url("/wcb")}","k1":"K1","minWithdrawable":1000,"maxWithdrawable":50000,"defaultDescription":"gift"}""")
                "/p" -> json("""{"tag":"payRequest","callback":"x","minSendable":1,"maxSendable":2,"metadata":"[]"}""")
                "/err" -> json("""{"status":"ERROR","reason":"voucher used"}""")
                "/auth" -> json("""{"tag":"login","k1":"x"}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        val w = lnurl.resolve(url("/w")) as LnurlResolved.Withdraw
        assertEquals(1L to 50L, w.params.minSats to w.params.maxSats)
        assertTrue(lnurl.resolve(url("/p")) is LnurlResolved.Pay)
        assertEquals("voucher used", assertThrows(LnurlException::class.java) { runBlocking { lnurl.resolve(url("/err")) } }.message)
        assertEquals("unsupported", assertThrows(LnurlException::class.java) { runBlocking { lnurl.resolve(url("/auth")) } }.code)
        assertEquals(500, assertThrows(LnurlException::class.java) { runBlocking { lnurl.resolve(url("/boom")) } }.status)
        assertNull(lnurl.resolve("satoshi@lawallet.io"))
    }

    @Test fun withdrawCallbackSurfacesTheServiceReason() = runBlocking {
        routes = { r -> if (r.requestUrl!!.queryParameter("k1") == "bad") json("""{"status":"ERROR","reason":"expired k1"}""") else json("""{"status":"OK"}""") }
        lnurl.submitWithdraw(url("/wcb"), "K1", "lnbc1x")
        assertEquals("K1", calls.last().requestUrl!!.queryParameter("k1"))
        assertEquals("lnbc1x", calls.last().requestUrl!!.queryParameter("pr"))
        assertEquals("expired k1", assertThrows(LnurlException::class.java) { runBlocking { lnurl.submitWithdraw(url("/wcb"), "bad", "lnbc1x") } }.message)
    }

    private fun cardService(maxMsat: Long) {
        routes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/card" -> json("""{"tag":"withdrawRequest","callback":"${url("/charge")}","k1":"CARDK1","minWithdrawable":10000,"maxWithdrawable":$maxMsat}""")
                "/charge" -> json("""{"status":"OK"}""")
                "/site" -> json("""{"tag":"payRequest","callback":"x","minSendable":1,"maxSendable":2,"metadata":"[]"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun boltcardChargesTheDisplayedInvoiceWithinLimits() = runBlocking {
        cardService(maxMsat = 50_000)
        val pay = BoltcardPay(lnurl)
        pay.payDisplayedInvoice(url("/card"), "lnbc1displayed", 21)
        assertEquals("lnbc1displayed", calls.last().requestUrl!!.queryParameter("pr")) // never a second invoice
        assertEquals("aboveMax", assertThrows(BoltcardPayException::class.java) { runBlocking { pay.payDisplayedInvoice(url("/card"), "lnbc1", 51) } }.code)
        assertEquals("belowMin", assertThrows(BoltcardPayException::class.java) { runBlocking { pay.payDisplayedInvoice(url("/card"), "lnbc1", 9) } }.code)
        assertEquals("notBoltcard", assertThrows(BoltcardPayException::class.java) { runBlocking { pay.payDisplayedInvoice(url("/site"), "lnbc1", 21) } }.code)
        assertEquals("noInvoice", assertThrows(BoltcardPayException::class.java) { runBlocking { pay.payDisplayedInvoice(url("/card"), "", 21) } }.code)
    }

    @Test fun drainedCardCannotPay() {
        cardService(maxMsat = 0)
        assertEquals("cannotPay", assertThrows(BoltcardPayException::class.java) { runBlocking { BoltcardPay(lnurl).payDisplayedInvoice(url("/card"), "lnbc1", 21) } }.code)
    }

    @Test fun scanRouterDispatchesEveryPayloadKind() = runBlocking {
        cardService(maxMsat = 50_000)
        val router = ScanRouter(lnurl)
        val hosts = setOf("beta.lawallet.io", "10.0.2.2:3052")
        val same = router.route("https://beta.lawallet.io/wallet/activate/abc123", hosts) as ScanResult.Activate
        assertTrue(same.sameInstance)
        assertEquals("abc123", same.tokenId)
        assertTrue(!(router.route("https://other.io/wallet/activate/abc123", hosts) as ScanResult.Activate).sameInstance)
        assertTrue(router.route(url("/card") + "?tag=withdrawRequest", hosts) is ScanResult.Withdraw)
        assertTrue((router.route("lightning:satoshi@lawallet.io", hosts) as ScanResult.Pay).destination is Destination.LnurlPay)
        assertEquals("unsupported", (router.route("just some text", hosts) as ScanResult.Error).code)
    }

    @Test fun instanceUrlNormalization() {
        assertEquals("https://beta.lawallet.io", Session.normalizeUrl(" beta.lawallet.io/ "))
        assertEquals("http://10.0.2.2:3052", Session.normalizeUrl("http://10.0.2.2:3052/wallet"))
        assertEquals("https://x.io", Session.normalizeUrl("https://x.io:443"))
        assertNull(Session.normalizeUrl(""))
    }

    @Test fun nwcTransactionsMapToSatsAndMilliseconds() {
        val t = Tx.from(NwcTransaction(type = "outgoing", paymentHash = "h", amount = 21_500, feesPaid = 2_000, createdAt = 1_700_000_000, settledAt = 1_700_000_005, description = " "))
        assertEquals(21L, t.amountSats)
        assertEquals(2L, t.feesPaidSats)
        assertEquals(1_700_000_005_000L, t.settledAt)
        assertEquals("settled", t.state)
        assertNull(t.description) // blank descriptions fall back to "Sent"/"Received" in the UI
        assertEquals("pending", Tx.from(NwcTransaction(paymentHash = "p")).state)
    }

    @Test fun nip04WithACachedSharedSecretMatchesThePerCallPath() {
        val a = Keys.generate()
        val b = Keys.generate()
        val shared = a.sharedX(b.pubkey)
        assertEquals("hi", Nip04.decrypt(Nip04.encrypt("hi", shared), b, a.pubkey))
        assertEquals("yo", Nip04.decrypt(Nip04.encrypt("yo", b, a.pubkey), shared))
    }
}

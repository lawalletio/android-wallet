package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.BackendCustodyException
import io.lawallet.wallet.core.api.Custody
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.ui.screens.RECOVERY_MERGE_REQUIRED
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.LocalSigner
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LaWalletApiTest {
    private val server = MockWebServer().apply { start() }
    private val keys = Keys.generate()
    private var stored: String? = null
    private val api = LaWalletApi(server.url("/").toString(), OkHttpClient(), { LocalSigner(keys) }, object : LaWalletApi.TokenStore {
        override fun load() = stored
        override fun save(token: String?) { stored = token }
    })

    @After fun tearDown() = server.shutdown()

    private fun jwt(expSecondsFromNow: Long) =
        "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${System.currentTimeMillis() / 1000 + expSecondsFromNow}}""".toByteArray()) + ".s"

    @Test fun custodyIsBothUnlessTheInstanceSaysOtherwise() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun custody(extra: String) = json.decodeFromString(RemoteWallet.serializer(), """{"id":"w","name":"n"$extra}""").custody
        assertEquals(Custody.BOTH, custody(""))
        assertEquals(Custody.BACKEND, custody(""","custody":"backend""""))
        assertEquals(Custody.LOCAL, custody(""","custody":"LOCAL""""))
        assertEquals(Custody.BOTH, custody(""","custody":"vault"""")) // unknown: today's behaviour, not a crash
    }

    @Test fun backendCustodyNeverFetchesTheConnection() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"${jwt(86_400)}","expiresIn":"24h","type":"Bearer"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"w1","name":"Vault","custody":"backend"}"""))
        assertThrows(BackendCustodyException::class.java) { runBlocking { api.remoteWalletConnectionString("w1") } }
        server.enqueue(MockResponse().setBody("""{"id":"w2","name":"Mine"}"""))
        server.enqueue(MockResponse().setBody("""{"connectionString":"nostr+walletconnect://x"}"""))
        assertEquals("nostr+walletconnect://x", api.remoteWalletConnectionString("w2"))
        val paths = List(server.requestCount) { server.takeRequest().path.orEmpty() }
        assertFalse(paths.toString(), paths.any { it.endsWith("w1/connection-string") })
        assertTrue(paths.toString(), paths.any { it.endsWith("w2/connection-string") })
    }

    @Test fun mintsJwtWithNip98AndReusesIt() = runBlocking {
        val token = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"token":"$token","expiresIn":"24h","type":"Bearer"}"""))
        server.enqueue(MockResponse().setBody("""{"userId":"u1","lightningAddress":null,"nwcString":"","effectiveNwcString":"nostr+walletconnect://x","currencyPrefs":null}"""))
        server.enqueue(MockResponse().setBody("""[]"""))

        val me = api.me()
        assertEquals("nostr+walletconnect://x", me.nwc)
        api.cards()

        val mint = server.takeRequest()
        assertEquals("/api/jwt", mint.path)
        val ev = NostrEvent.json.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(mint.getHeader("Authorization")!!.removePrefix("Nostr "))))
        assertEquals(server.url("/api/jwt").toString(), ev.tag("u"))
        assertEquals(sha256(mint.body.readByteArray()).toHex(), ev.tag("payload"))
        assertTrue(ev.verify())
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization")) // no second mint
    }

    @Test fun remintsOnceOn401() = runBlocking {
        stored = jwt(86_400)
        val fresh = jwt(86_400) + "x"
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired","code":"AUTH"}}"""))
        server.enqueue(MockResponse().setBody("""{"token":"$fresh"}"""))
        server.enqueue(MockResponse().setBody("""[]"""))
        assertEquals(0, api.remoteWallets().size)
        assertEquals(fresh, stored)
    }

    /** A wallet minted at another LNCurl server is saved tagged LNCurl with its origin (the server's strict NWC config). */
    @Test fun createsClientMintedLncurlWalletTagged() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"w1","name":"Masize","type":"NWC","status":"ACTIVE","isDefault":false,"provider":"lncurl"}"""))
        val w = api.createNwcWallet("Masize", "nostr+walletconnect://x", "SEND_RECEIVE", false, lncurlServerUrl = "https://lncurl.masize.com")
        assertEquals("lncurl", w.provider)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, "\"provider\":\"lncurl\"" in body && "\"lncurlServerUrl\":\"https://lncurl.masize.com\"" in body)
    }

    @Test fun deletesAccountWithBearerAndConfirmation() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"deleted":true}"""))
        assertTrue(api.deleteAccount())
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/account", req.path)
        assertEquals("Bearer $stored", req.getHeader("Authorization"))
        assertEquals("""{"confirm":"DELETE"}""", req.body.readUtf8())
    }

    @Test fun deleteAccountFallsBackWhenEndpointMissing() {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Cannot delete the last admin account","code":"AUTHORIZATION_ERROR"}}"""))
        assertFalse(runBlocking { api.deleteAccount() })
        assertFalse(runBlocking { api.deleteAccount() })
        val e = assertThrows(ApiException::class.java) { runBlocking { api.deleteAccount() } }
        assertEquals(403, e.status)
    }

    @Test fun surfacesPaymentRequiredForPaidAddresses() {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Payment required","code":"PAYMENT_REQUIRED"}}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.createAddress("satoshi") } }
        assertEquals(402, e.status)
        assertEquals("Payment required", e.message)
    }

    // ---------- card recovery ----------

    @Test fun previewDefaultsToActivateAndReadsRecoverPurpose() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tokenId":"t1","qrKind":"ONE_TIME","status":"PENDING"}"""))
        server.enqueue(MockResponse().setBody("""{"tokenId":"t2","qrKind":"ONE_TIME","status":"PENDING","purpose":"RECOVER"}"""))
        assertEquals("ACTIVATE", api.activationPreview("t1").purpose) // older servers send no purpose
        assertEquals("RECOVER", api.activationPreview("t2").purpose)
    }

    @Test fun recoveryCheckDecodesBothSides() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"sameAccount":false,"mergeRequired":true,
            "existing":{"lightningAddresses":["me@x.io"],"cards":1,"remoteWallets":1},
            "incoming":{"lightningAddresses":["old@x.io","old2@x.io"],"cards":2,"remoteWallets":2}}"""))
        val check = api.recoveryCheck("tok")
        assertTrue(check.mergeRequired)
        assertEquals(listOf("old@x.io", "old2@x.io"), check.incoming.lightningAddresses)
        assertEquals(1, check.existing.cards)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/activation-tokens/tok/recovery-check", req.path)
    }

    @Test fun recoverClaimSendsConfirmMergeAndDecodesTheSummary() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"qrKind":"ONE_TIME","purpose":"RECOVER","outcome":"recovered",
            "recovery":{"lightningAddresses":["old@x.io"],"primaryAddress":"old@x.io",
              "cards":[{"id":"c1","title":null,"designImageUrl":"/card.png"}],
              "remoteWallets":[{"id":"w1","name":"LNCurl wallet (2)","isDefault":false}],
              "vouchers":0,"mergedIntoExisting":true},
            "needsLightningAddress":false}"""))
        val res = api.claimActivation("tok", confirmMerge = true)
        assertEquals("recovered", res.outcome)
        assertTrue(res.recovery!!.mergedIntoExisting)
        assertEquals("LNCurl wallet (2)", res.recovery!!.remoteWallets.single().name)
        assertNull(res.recovery!!.cards.single().title)
        assertEquals("""{"remoteWalletId":null,"confirmMerge":true}""", server.takeRequest().body.readUtf8())
    }

    @Test fun plainActivationClaimBodyIsUnchanged() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"qrKind":"ONE_TIME","needsLightningAddress":true}"""))
        val res = api.claimActivation("tok")
        assertNull(res.outcome)
        assertEquals("""{"remoteWalletId":null}""", server.takeRequest().body.readUtf8())
    }

    @Test fun mergeRequiredConflictExposesTheReason() {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"success":false,"error":{"message":"This account already has data.","code":"CONFLICT","details":{"reason":"RECOVERY_MERGE_REQUIRED"}}}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.claimActivation("tok") } }
        assertEquals(409, e.status)
        assertEquals(RECOVERY_MERGE_REQUIRED, e.reason)
    }

    @Test fun meReadsRecoveryFieldsAndToleratesOlderServers() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"userId":"u1","cardRecoveryEnabled":false,
            "accountTransfer":{"id":"r1","transferredAt":"2026-10-21T12:43:36.097Z","cardTitle":null,
              "lightningAddresses":["old@x.io"],"cards":1,"remoteWallets":1}}"""))
        server.enqueue(MockResponse().setBody("""{"userId":"u1"}"""))
        val me = api.me()
        assertEquals(false, me.cardRecoveryEnabled)
        assertEquals("r1", me.accountTransfer!!.id)
        val old = api.me()
        assertNull(old.cardRecoveryEnabled)
        assertNull(old.accountTransfer)
    }

    @Test fun togglesCardRecoveryAndDismissesTheTransferNotice() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"cardRecoveryEnabled":false}"""))
        server.enqueue(MockResponse().setBody("""{"dismissed":1}"""))
        assertFalse(api.setCardRecovery(false))
        api.dismissAccountTransfer()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/users/me/card-recovery", put.path)
        assertEquals("""{"enabled":false}""", put.body.readUtf8())
        val dismiss = server.takeRequest()
        assertEquals("POST", dismiss.method)
        assertEquals("/api/users/me/account-transfer/dismiss", dismiss.path)
    }

    @Test fun readsAPollAndSubmitsARanking() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"poll":{"id":"p1","slug":"usdt-tech","title":"USDT tech","type":"RANKING","options":[{"id":"tron","label":"Tron"},{"id":"liquid","label":"Liquid"}],"allowCustom":true,"updatable":false,"status":"OPEN","createdAt":"x","updatedAt":"x"},"response":null}"""))
        server.enqueue(MockResponse().setBody("""{"response":{"choices":["liquid","custom:Ethereum L1","tron"],"updatedAt":"2026-11-16T00:00:00Z"}}"""))

        val state = api.poll("usdt-tech")
        assertTrue(state.poll.ranking && state.poll.open && state.response == null && !state.locked)
        assertEquals("Liquid", state.label("liquid"))
        assertEquals("Ethereum L1", state.label("custom:Ethereum L1"))
        val saved = api.submitPoll("usdt-tech", listOf("liquid", "custom:Ethereum L1", "tron"))
        assertEquals("liquid", saved.choices.first())
        assertTrue(state.copy(response = saved).locked) // one-time poll

        assertEquals("/api/polls/usdt-tech", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/polls/usdt-tech/response", put.path)
        assertEquals("""{"choices":["liquid","custom:Ethereum L1","tron"]}""", put.body.readUtf8())
    }

    @Test fun lockedPollSurfacesItsCode() {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"message":"Already answered","code":"POLL_LOCKED"}}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.submitPoll("usdt-tech", listOf("tron")) } }
        assertEquals(409, e.status)
        assertEquals("POLL_LOCKED", e.code)
    }

    @Test fun textPollIsNotARanking() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"poll":{"id":"p2","slug":"wishes","title":"Wishes","type":"TEXT","options":[],"allowCustom":false,"updatable":true,"status":"OPEN"},"response":{"choices":["custom:Cheaper fees"],"updatedAt":"x"}}"""))
        val state = api.poll("wishes")
        assertTrue(state.poll.freeText && !state.poll.ranking)
        assertEquals("Cheaper fees", state.label(state.response!!.choices.first()))
    }

    // ---------- ARS cash-out ----------
    @Test fun swapQuoteKeepsAFailedProvider() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""[{"provider":"bullbitcoin","sats":44567,"arsPerBtc":112190000.5,"minArs":null},{"provider":"wapu","sats":null,"arsPerBtc":null,"minArs":null,"error":"Wapu: down"}]"""))
        val q = api.swapQuote(50000.0)
        assertEquals("/api/wallet/swaps/quote?amountArs=50000", server.takeRequest().path)
        assertEquals(44567L, q[0].sats)
        assertNull(q[1].sats)
        assertEquals("Wapu: down", q[1].error)
    }

    @Test fun bankLookupLowercasesAndMapsMisses() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"cvu":"0000003100010000000001","alias":"ana.mp","bank":"Mercado Pago","owners":[{"type":"F","cuit":"27123456784","name":"Ana Pérez"}]}"""))
        val a = api.bankAccount(" Ana.MP ")
        assertEquals("/api/wallet/alias/ana.mp", server.takeRequest().path)
        assertEquals("Ana Pérez", a.owners.single().name)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Alias lookup rate limit reached","code":"TOO_MANY_REQUESTS"}}"""))
        assertEquals(429, assertThrows(ApiException::class.java) { runBlocking { api.bankAccount("otro.alias") } }.status)
    }

    @Test fun createSwapSendsTheSchemaBody() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"s1","provider":"wapu","destination":"ana.mp","holderName":"Ana","amountArs":20000,"amountSats":17800,"bolt11":"lnbc1","paymentHash":"ab","status":"PENDING","expiresAt":"2026-11-23T12:05:00.000Z","paidAt":null}"""))
        val s = api.createSwap("wapu", 20000.0, "ana.mp", "Ana")
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/wallet/swaps", req.path)
        assertEquals("""{"provider":"wapu","amountArs":20000.0,"destination":"ana.mp","holderName":"Ana"}""", req.body.readUtf8())
        assertEquals(17800L, s.amountSats)
        assertEquals("PENDING", s.status)
    }

    @Test fun paidCancelAndReceipt() = runBlocking {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody("""{"id":"s1","provider":"bullbitcoin","status":"PENDING","paidAt":"2026-11-23T12:01:00.000Z"}"""))
        assertEquals("2026-11-23T12:01:00.000Z", api.swapPaid("s1", "00".repeat(32)).paidAt)
        assertEquals("""{"preimage":"${"00".repeat(32)}"}""", server.takeRequest().body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"message":"Only an unpaid order can be canceled","code":"CONFLICT"}}"""))
        assertEquals(409, assertThrows(ApiException::class.java) { runBlocking { api.cancelSwap("s1") } }.status)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("%PDF-1.7").setHeader("content-type", "application/pdf"))
        assertEquals("%PDF-1.7", String(api.swapReceipt("s1", share = true)))
        val req = server.takeRequest()
        assertEquals("/api/wallet/swaps/s1/receipt?variant=share", req.path)
        assertEquals("Bearer $stored", req.getHeader("Authorization"))
    }
}

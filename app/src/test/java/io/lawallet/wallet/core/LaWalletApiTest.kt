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
import okhttp3.mockwebserver.RecordedRequest
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

    // ---------- one request per endpoint: method, path, body, decoded answer ----------

    /** Answers [body] to one authed [call]; returns its result and the request the server saw. */
    private fun <T> exchange(body: String, call: suspend LaWalletApi.() -> T): Pair<T, RecordedRequest> {
        stored = jwt(86_400)
        server.enqueue(MockResponse().setBody(body))
        val result = runBlocking { api.call() }
        return result to server.takeRequest()
    }

    private fun RecordedRequest.line() = "$method $path"

    @Test fun publicSettingsVersionAndUsernameCheckSendNoToken() {
        val (s, r1) = exchange("""{"domain":"x.io","integration_wapu":"true","email_login_enabled":"true","future_key":1}""") { settings() }
        assertEquals("GET /api/settings", r1.line()); assertNull(r1.getHeader("Authorization"))
        assertTrue(s.swapsEnabled && s.emailLoginEnabled && !s.bankLookupEnabled)
        val (v, r2) = exchange("""{"currentVersion":"1.2.3"}""") { version() }
        assertEquals("GET /api/version", r2.line()); assertEquals("1.2.3", v.currentVersion)
        val (u, r3) = exchange("""{"available":false}""") { checkUsername("ana maría") }
        assertEquals("GET /api/lightning-addresses/check?username=ana%20mar%C3%ADa", r3.line()); assertNull(r3.getHeader("Authorization"))
        assertFalse(u.available)
    }

    @Test fun emailLoginRequestPreviewAndVerify() {
        val (_, r1) = exchange("""{"ok":true}""") { requestEmailLogin("a@b.c", "es") }
        assertEquals("POST /api/auth/email/request", r1.line())
        assertEquals("""{"email":"a@b.c","locale":"es","context":"wallet"}""", r1.body.readUtf8())
        val (p, r2) = exchange("""{"isNew":true,"email":"a•••c@b.c"}""") { emailLoginPreview("tok") }
        assertEquals("POST /api/auth/email/preview", r2.line()); assertEquals("""{"token":"tok"}""", r2.body.readUtf8())
        assertTrue(p.isNew); assertNull(p.displayName)
        val (v, r3) = exchange("""{"secretHex":"00","pubkey":"pk"}""") { emailLoginVerify("tok") }
        assertEquals("POST /api/auth/email/verify", r3.line()); assertNull(r3.getHeader("Authorization"))
        assertEquals("pk", v.pubkey); assertFalse(v.isNew)
    }

    @Test fun addressesListCreatePrimaryAndInvoice() {
        val (list, r1) = exchange("""[{"username":"ana","isPrimary":true,"mode":"CUSTOM_NWC","remoteWalletId":"w1"},{"username":"b"}]""") { addresses() }
        assertEquals("GET /api/wallet/addresses", r1.line()); assertEquals("Bearer $stored", r1.getHeader("Authorization"))
        assertEquals("w1", list[0].remoteWalletId); assertEquals("IDLE", list[1].mode)
        val (a, r2) = exchange("""{"username":"ana2","mode":"IDLE"}""") { createAddress("ana2", mode = "IDLE") }
        assertEquals("POST /api/wallet/addresses", r2.line()); assertEquals("""{"username":"ana2","mode":"IDLE"}""", r2.body.readUtf8())
        assertEquals("ana2", a.username)
        assertEquals("""{"username":"x"}""", exchange("""{"username":"x"}""") { createAddress("x") }.second.body.readUtf8())
        assertEquals("POST /api/wallet/addresses/ana%202/primary", exchange("{}") { setPrimaryAddress("ana 2") }.second.line())
        val (inv, r3) = exchange("""{"id":"i1","bolt11":"lnbc1","amountSats":1000,"free":false}""") { createAddressInvoice("ana") }
        assertEquals("POST /api/invoices", r3.line())
        assertEquals("""{"purpose":"wallet-address","metadata":{"username":"ana"}}""", r3.body.readUtf8())
        assertEquals(1000L, inv.amountSats)
        val (claim, r4) = exchange("""{"success":true,"lightningAddress":"ana@x.io"}""") { claimInvoice("i1", "ab") }
        assertEquals("POST /api/invoices/i1/claim", r4.line()); assertEquals("""{"preimage":"ab"}""", r4.body.readUtf8())
        assertEquals("ana@x.io", claim.lightningAddress)
    }

    @Test fun addressRoutingAndRemoval() {
        val route = exchange("{}") { routeAddressToWallet("ana", "w1") }.second
        assertEquals("PUT /api/wallet/addresses/ana", route.line())
        assertEquals("""{"mode":"CUSTOM_NWC","remoteWalletId":"w1"}""", route.body.readUtf8())
        assertEquals("""{"mode":"ALIAS","redirect":"x@y.z"}""", exchange("{}") { redirectAddress("ana", "x@y.z") }.second.body.readUtf8())
        assertEquals("""{"mode":"IDLE"}""", exchange("{}") { freeAddress("ana") }.second.body.readUtf8())
        val del = exchange("") { deleteAddress("ana") }.second
        assertEquals("DELETE /api/wallet/addresses/ana", del.line()); assertEquals(0L, del.bodySize)
    }

    @Test fun remoteWalletReadUpdateDelete() {
        val (w, r1) = exchange("""{"id":"w1","name":"Alby","status":"DISABLED","provider":"lncurl"}""") { remoteWallet("w1") }
        assertEquals("GET /api/remote-wallets/w1", r1.line()); assertFalse(w.isActive); assertTrue(w.isLncurl)
        val (b, r2) = exchange("""{"balanceSats":21}""") { remoteWalletBalance("w1") }
        assertEquals("GET /api/remote-wallets/w1/balance", r2.line()); assertEquals(21L, b)
        val (u, r3) = exchange("""{"id":"w1","name":"New","isDefault":true}""") { updateRemoteWallet("w1", name = "New", isDefault = true) }
        assertEquals("PATCH /api/remote-wallets/w1", r3.line()); assertEquals("""{"name":"New","isDefault":true}""", r3.body.readUtf8())
        assertTrue(u.isDefault)
        assertEquals("""{"status":"ACTIVE"}""", exchange("""{"id":"w1","name":"n"}""") { updateRemoteWallet("w1", status = "ACTIVE") }.second.body.readUtf8())
        assertEquals("DELETE /api/remote-wallets/w1", exchange("") { deleteRemoteWallet("w1") }.second.line())
        assertEquals("DELETE /api/remote-wallets/w1?permanent=true", exchange("") { deleteRemoteWallet("w1", permanent = true) }.second.line())
    }

    @Test fun cardsBindLinkAndEnable() {
        val card = """{"id":"c1","remoteWalletId":"w2","disabled":true,"unknown":"x"}"""
        val (c, r1) = exchange(card) { bindCardToWallet("c1", "w2") }
        assertEquals("PATCH /api/wallet/cards/c1", r1.line()); assertEquals("""{"remoteWalletId":"w2"}""", r1.body.readUtf8())
        assertEquals("w2", c.remoteWalletId); assertTrue(c.disabled); assertEquals("SIMPLE", c.kind)
        assertEquals("""{"linkDefaultWallet":true}""", exchange(card) { linkCardToDefaultWallet("c1") }.second.body.readUtf8())
        assertEquals("""{"enabled":false}""", exchange(card) { setCardEnabled("c1", false) }.second.body.readUtf8())
    }

    @Test fun passkeysAndOauthGrants() {
        val (keys, r1) = exchange("""{"credentials":[{"id":"k1","label":"Pixel","backedUp":true}]}""") { passkeys() }
        assertEquals("GET /api/auth/passkey/credentials", r1.line()); assertEquals("Pixel", keys.single().label)
        val rename = exchange("{}") { renamePasskey("k1", "Phone") }.second
        assertEquals("PATCH /api/auth/passkey/credentials/k1", rename.line()); assertEquals("""{"label":"Phone"}""", rename.body.readUtf8())
        assertEquals("DELETE /api/auth/passkey/credentials/k1", exchange("") { deletePasskey("k1") }.second.line())
        val (grants, r2) = exchange("""{"grants":[{"id":"g1","clientName":"Shop","scopes":["pay"],"spendLimitSats":5000}]}""") { oauthGrants() }
        assertEquals("GET /api/oauth/grants", r2.line()); assertEquals(5000L, grants.single().spendLimitSats)
        assertEquals("DELETE /api/oauth/grants/g1", exchange("") { revokeGrant("g1") }.second.line())
        assertEquals(emptyList<Any>(), exchange("{}") { oauthGrants() }.first) // older server: no list
    }

    @Test fun swapsListAndOne() {
        val (list, r1) = exchange("""{"items":[{"id":"s1","provider":"wapu","status":"COMPLETED","updatedAt":"2026-11-23T12:00:00Z"}],"total":1}""") { swaps() }
        assertEquals("GET /api/wallet/swaps?page=1&pageSize=50", r1.line()); assertEquals("COMPLETED", list.single().status)
        val (s, r2) = exchange("""{"id":"s 1","provider":"bullbitcoin"}""") { swap("s 1") }
        assertEquals("GET /api/wallet/swaps/s%201", r2.line()); assertEquals("PENDING", s.status)
    }
}

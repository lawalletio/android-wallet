package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.LaWalletApi
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
}

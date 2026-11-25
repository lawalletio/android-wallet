package io.lawallet.wallet.core

import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.nwc.NwcUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NwcClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val wallets = mutableListOf<FakeNwcWallet>()

    @After fun tearDown() {
        scope.cancel()
        wallets.forEach { w -> w.dropConnections(); runCatching { w.server.shutdown() } }
    }

    private fun client(nip44: Boolean): Pair<FakeNwcWallet, NwcClient> {
        val w = FakeNwcWallet(nip44).start().also { wallets += it }
        return w to NwcClient(NwcUri.parse(w.connectionString), http)
    }

    @Test fun parsesConnectionString() {
        val uri = NwcUri.parse("nostr+walletconnect://" + "a".repeat(64) + "?relay=wss%3A%2F%2Frelay.getalby.com%2Fv1&relay=wss://b&secret=" + "0".repeat(63) + "1&lud16=x%40y.z")
        assertEquals(listOf("wss://relay.getalby.com/v1", "wss://b"), uri.relays)
        assertEquals("x@y.z", uri.lud16)
        assertThrows(IllegalArgumentException::class.java) { NwcUri.parse("https://nope") }
    }

    @Test fun nip04Requests() = runBlocking { exercise(nip44 = false) }

    @Test fun nip44Requests() = runBlocking { exercise(nip44 = true) }

    private suspend fun exercise(nip44: Boolean) = withTimeout(20_000) {
        val (wallet, nwc) = client(nip44)
        assertEquals(21_000_000L, nwc.getBalanceMsat())
        assertEquals(5000L, nwc.makeInvoice(5000, "coffee").amount)
        assertEquals(1_700_000_100L, nwc.lookupInvoice("abc").settledAt)
        assertEquals(listOf("incoming", "outgoing"), nwc.listTransactions(limit = 25).map { it.type })
        assertEquals(1000L, nwc.payInvoice("lnbc1x").feesPaid)
        wallet.balanceMsat = 0
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(NwcException::class.java) { runBlocking { nwc.payInvoice("lnbc1x") } }.code)
    }

    /** A relay that misses the info lookup once must not pin a nip44 wallet to nip04 for good. */
    @Test fun unansweredInfoLookupIsRetried() = runBlocking {
        withTimeout(20_000) {
            val w = FakeNwcWallet(nip44 = true).apply { answerInfo = false }.start().also { wallets += it }
            val nwc = NwcClient(NwcUri.parse(w.connectionString), http, infoTimeoutMs = 300)
            val e = assertThrows(NwcException::class.java) { runBlocking { nwc.requestRaw("get_balance", JsonObject(emptyMap()), timeoutMs = 1_000) } }
            assertEquals("TIMEOUT", e.code)
            assertEquals(listOf("wrong-encryption"), w.requests.toList())
            w.answerInfo = true
            assertEquals(21_000_000L, nwc.getBalanceMsat())
        }
    }

    @Test fun notificationsSurviveReconnect() = runBlocking {
        withTimeout(20_000) {
            val (wallet, nwc) = client(nip44 = true)
            nwc.getBalanceMsat() // connected + encryption negotiated
            val first = async { nwc.notifications().first() }
            delay(300)
            wallet.notify(FakeNwcWallet.tx("incoming", 42_000, "n1"))
            assertEquals("n1", first.await().tx.paymentHash)

            val second = async { nwc.notifications().first() }
            delay(300)
            wallet.dropConnections()
            withTimeout(10_000) { while (nwc.relay.connected.value) delay(20); while (!nwc.relay.connected.value) delay(50); delay(300) }
            wallet.notify(FakeNwcWallet.tx("incoming", 7_000, "n2"))
            val n = second.await()
            assertEquals("payment_received", n.type)
            assertEquals(7_000L, n.tx.amount)
            }
    }

    /** Switching away from a wallet and back (a new NwcClient each time) reuses the one pooled socket. */
    @Test fun switchingClientsReusesTheSocket() = runBlocking {
        withTimeout(20_000) {
            val (wallet, first) = client(nip44 = true)
            first.getBalanceMsat()
            val ntf = async { first.notifications().first() }
            delay(300)
            ntf.cancel() // wallet switch: the old stream CLOSEs its sub
            val again = NwcClient(NwcUri.parse(wallet.connectionString), http)
            assertEquals(21_000_000L, again.getBalanceMsat())
            assertEquals(1, wallet.server.requestCount)
        }
    }

    @Test fun parseRejectsMissingSecretRelayOrBadKeys() {
        val pk = "a".repeat(64)
        val secret = "0".repeat(63) + "1"
        fun bad(raw: String) = assertThrows(raw, IllegalArgumentException::class.java) { NwcUri.parse(raw) }
        bad("nostr+walletconnect://$pk?relay=wss://r")
        bad("nostr+walletconnect://$pk?secret=$secret")
        bad("nostr+walletconnect://$pk")
        bad("nostr+walletconnect://${"a".repeat(63)}?relay=wss://r&secret=$secret") // short pubkey
        bad("nostr+walletconnect://$pk?relay=wss://r&secret=${"zz".repeat(32)}") // not hex
        bad("nostr+walletconnect://$pk?relay=wss://r&secret=${"0".repeat(63)}") // odd length
        bad("nostr+walletconnect://$pk?relay=wss://r&secret=${"0".repeat(64)}") // not a valid key
        assertEquals(pk, NwcUri.parse(" NOSTR+WALLETCONNECT://${pk.uppercase()}/?relay=wss://r&secret=$secret ").walletPubkey)
        assertEquals(pk, NwcUri.parse("nostrwalletconnect://$pk?relay=wss://r&secret=$secret").walletPubkey)
    }

    /** The REQ filter for notifications, captured off the relay's outgoing frames. */
    private suspend fun notificationKinds(nip44: Boolean): String {
        val (wallet, nwc) = client(nip44)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val socket = nwc.relay.sink
        nwc.relay.sink = { sent += it; socket(it) }
        val first = scope.async { nwc.notifications().first() }
        withTimeout(10_000) { while (sent.none { "\"ntf-" in it }) delay(20) }
        wallet.notify(FakeNwcWallet.tx("incoming", 1_000, "k1"))
        assertEquals("k1", withTimeout(10_000) { first.await() }.tx.paymentHash)
        return Regex("\"kinds\":\\[([0-9,]+)]").find(sent.first { it.startsWith("[\"REQ\"") && "\"ntf-" in it })!!.groupValues[1]
    }

    // regression: 9a4eb0e fix(notifications): one notification per incoming payment (#19)
    @Test fun regression_9a4eb0e_subscribesToTheOneNotificationKindOfTheEncryption() = runBlocking {
        // A wallet that publishes each payment on both kinds would otherwise deliver it twice.
        assertEquals("23197", notificationKinds(nip44 = true))
        assertEquals("23196", notificationKinds(nip44 = false))
    }
}

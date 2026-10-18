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
        return w to NwcClient(NwcUri.parse(w.connectionString), http, scope)
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
        nwc.close()
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
            nwc.close()
        }
    }
}

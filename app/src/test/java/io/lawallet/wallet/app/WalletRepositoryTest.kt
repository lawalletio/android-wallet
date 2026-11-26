package io.lawallet.wallet.app

import android.app.Application
import io.lawallet.wallet.core.FakeNwcWallet
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.WalletAddress
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import io.lawallet.wallet.core.nwc.NwcClient
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WalletRepositoryTest {
    private val h = RepoHarness()
    private val fakes = mutableListOf<FakeNwcWallet>()

    @After fun tearDown() {
        h.close()
        fakes.forEach { it.dropConnections(); runCatching { it.server.shutdown() } }
    }

    private val primaryKeys = Keys.generate()
    private val yKeys = Keys.generate()
    private val primaryNwc = nwcString(primaryKeys)
    private val yNwc = nwcString(yKeys)
    private val addresses = listOf(
        WalletAddress("agustin", mode = "CUSTOM_NWC", remoteWalletId = "wP", isPrimary = true),
        WalletAddress("yuyyy", mode = "CUSTOM_NWC", remoteWalletId = "wY"),
        WalletAddress("fwd", mode = "ALIAS", redirect = "x@y.z"),
        WalletAddress("idle"),
    )
    private val addressesJson = io.lawallet.wallet.core.api.LaWalletApi.json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(WalletAddress.serializer()), addresses,
    )
    private val me = CurrentUser("u1", lightningAddress = "agustin@x.io", effectiveNwcString = primaryNwc)

    private fun connectedTo(): String? = (h.wallet.client.value as? NwcClient)?.uri?.walletPubkey
    private fun serveY(custody: String? = null, code: Int = 200) {
        h.instance.on("GET /api/remote-wallets/wY", """{"id":"wY","name":"Y"${custody?.let { ""","custody":"$it"""" } ?: ""}}""", code)
        h.instance.on("GET /api/remote-wallets/wY/connection-string", """{"connectionString":"$yNwc"}""", code)
    }

    /** Logged in on the primary, the address list loaded, then [username] picked on Home. */
    private fun selecting(username: String) = runBlocking {
        h.instance.on("GET /api/wallet/addresses", addressesJson)
        h.login(me)
        eventually(message = "primary connected") { connectedTo() == primaryKeys.pubkey && h.wallet.addresses.value.size == 4 }
        h.wallet.selectAddress(username)
    }

    // ---- pure-ish lookups ----

    @Test fun walletOfIsOnlyANonPrimaryAddressOwnWallet() {
        val w = h.wallet
        assertEquals("wY", w.walletOf("yuyyy", addresses))
        assertNull(w.walletOf("agustin", addresses)) // the primary's wallet is the account's, resolved from /me
        assertNull(w.walletOf("fwd", addresses))
        assertNull(w.walletOf("idle", addresses))
        assertNull(w.walletOf("gone", addresses))
        assertNull(w.walletOf(null, addresses))
    }

    @Test fun switchableListsThePrimaryFirst() {
        // Stable: the rest keep the server's order.
        assertEquals(listOf("agustin", "idle", "fwd", "yuyyy"), h.wallet.switchable(addresses.reversed()).map { it.username })
    }

    @Test fun shownAddressIsTheSelectionWhileItExistsElseThePrimary() {
        val w = h.wallet
        w.setAddresses(addresses)
        assertNull(w.shownAddress(null))
        assertEquals("agustin@x.io", w.shownAddress(me))
        w.selectAddress("yuyyy"); assertEquals("yuyyy@x.io", w.shownAddress(me))
        w.selectAddress("agustin"); assertEquals("agustin@x.io", w.shownAddress(me))
        w.selectAddress("gone"); assertEquals("agustin@x.io", w.shownAddress(me))
        assertEquals("gone", h.prefs.selectedAddress) // the pick is kept; it just isn't shown while missing
    }

    // ---- resolveFor ----

    // regression: 584b858 fix(wallet): pay and receive from the selected address's wallet
    @Test fun regression_584b858_selectedAddressPaysFromItsOwnWallet() = runBlocking {
        serveY()
        selecting("yuyyy")
        eventually(message = "switched to yuyyy's wallet") { connectedTo() == yKeys.pubkey }
        // A send re-reads the connection: still yuyyy's wallet, never the primary's.
        assertEquals(yKeys.pubkey, (h.wallet.freshClient() as NwcClient).uri.walletPubkey)
        h.wallet.selectAddress(null)
        eventually(message = "back on the primary") { connectedTo() == primaryKeys.pubkey }
    }

    // regression: 584b858 fix(wallet): pay and receive from the selected address's wallet
    @Test fun regression_584b858_unresolvableSelectedWalletNeverFallsBackToThePrimary() = runBlocking {
        serveY(code = 500)
        selecting("yuyyy")
        eventually(message = "disconnected") { h.wallet.client.value == null }
        assertNull(h.wallet.freshClient())
        Thread.sleep(200)
        assertNull(h.wallet.client.value)
        assertNull(h.wallet.ensureWallet()) // nor mints the account a new default for it
        assertTrue(h.instance.hits.none { it.startsWith("POST /api/remote-wallets/lncurl") })
    }

    @Test fun offlineKeepsTheLastResolvedConnection() = runBlocking {
        serveY()
        selecting("yuyyy")
        eventually { connectedTo() == yKeys.pubkey }
        serveY(code = 503)
        assertEquals(yKeys.pubkey, (h.wallet.freshClient() as NwcClient).uri.walletPubkey)
    }

    @Test fun backendCustodyDropsTheCachedConnection() = runBlocking {
        serveY()
        selecting("yuyyy")
        eventually { connectedTo() == yKeys.pubkey }
        assertEquals(yNwc, h.prefs.secretCached("nwc:wY"))
        serveY(custody = "backend")
        assertNull(h.wallet.freshClient())
        assertNull(h.prefs.secretCached("nwc:wY"))
        assertTrue(h.instance.hits.count { it == "GET /api/remote-wallets/wY/connection-string" } == 1)
    }

    // ---- notice() dedupe, through the wallet's own notifications ----

    private fun fakeWallet(): Pair<FakeNwcWallet, String> {
        val f = FakeNwcWallet(nip44 = true).start().also { fakes += it }
        return f to sha256(f.connectionString.toByteArray()).toHex().take(16)
    }

    private val notices = CopyOnWriteArrayList<PaymentNotice>()

    /** Logs in on [f]; returns once its notifications reach Home (a warm-up payment got through). */
    private fun listening(f: FakeNwcWallet) = runBlocking {
        h.scope.launch { h.wallet.notices.collect { notices += it } }
        h.login(me.copy(effectiveNwcString = f.connectionString))
        deliver(f, FakeNwcWallet.tx("incoming", 1000, "warmup"))
    }

    /** Sends [tx] until it's noticed (a notification before the subscription is up is lost). */
    private fun deliver(f: FakeNwcWallet, tx: kotlinx.serialization.json.JsonObject, type: String = "payment_received") {
        val hash = tx["payment_hash"].toString().trim('"')
        val until = System.currentTimeMillis() + 10_000
        while (notices.none { it.tx.paymentHash == hash } && System.currentTimeMillis() < until) { f.notify(tx, type); Thread.sleep(150) }
        assertTrue("$hash noticed", notices.any { it.tx.paymentHash == hash })
    }

    private fun hashless(sats: Long, at: Long) = buildJsonObject { put("type", "incoming"); put("amount", sats * 1000); put("created_at", at); put("settled_at", at) }

    // regression: 9a4eb0e fix(notifications): one notification per incoming payment (#19)
    @Test fun regression_9a4eb0e_onePaymentOneNotice() {
        val (f, _) = fakeWallet()
        listening(f)
        f.notify(FakeNwcWallet.tx("incoming", 5000, "h9"))
        f.notify(FakeNwcWallet.tx("incoming", 5000, "h9")) // the same payment again (another kind, a catch-up row)
        f.notify(FakeNwcWallet.tx("incoming", 5000, "h9"), "payment_sent")
        // Two different payments without a hash are two notices, not one constant key.
        f.notify(hashless(1, 1_700_000_001))
        f.notify(hashless(2, 1_700_000_002))
        deliver(f, FakeNwcWallet.tx("incoming", 1000, "last"))
        assertEquals(1, notices.count { it.tx.paymentHash == "h9" })
        assertEquals(listOf(1L, 2L), notices.filter { it.tx.paymentHash.isEmpty() }.map { it.tx.amountSats })
    }

    @Test fun legacyTypeAndHashKeysStillCountAsSeen() {
        val (f, key) = fakeWallet()
        h.prefs.setSeen(key, listOf("payment_received:old"))
        listening(f)
        f.notify(FakeNwcWallet.tx("incoming", 5000, "old"))
        deliver(f, FakeNwcWallet.tx("incoming", 1000, "after"))
        assertTrue(notices.none { it.tx.paymentHash == "old" })
    }

    @Test fun seenKeysKeepTheNewest200() {
        val (f, key) = fakeWallet()
        h.prefs.setSeen(key, List(200) { "old$it" })
        listening(f)
        val seen = h.prefs.seen(key)
        assertEquals(200, seen.size)
        assertEquals("warmup", seen.last())
        assertTrue("oldest dropped", "old0" !in seen && "old1" in seen)
        f.notify(FakeNwcWallet.tx("incoming", 5000, "old199"))
        deliver(f, FakeNwcWallet.tx("incoming", 1000, "after"))
        assertTrue(notices.none { it.tx.paymentHash == "old199" })
    }

    // A balance read sent before a payment and answered after it stood in for the refresh the payment asked for, so
    // Home kept the old balance until the next poll.
    @Test fun aRefreshNeverSharesABalanceReadSentBeforeIt() = runBlocking {
        val (f, _) = fakeWallet()
        listening(f)
        eventually { h.wallet.balance.value == 21_000L }
        f.holdBalance = true
        val before = h.scope.async { h.wallet.refreshBalance() }
        eventually { f.heldBalances.isNotEmpty() }
        f.holdBalance = false
        f.balanceMsat -= 1_000_000 // the payment lands while that read is still out
        val after = h.scope.async { h.wallet.refreshBalance() }
        f.releaseBalances() // the old read answers 21,000, then the refresh's own read goes out
        withTimeout(10_000) { before.await(); after.await() }
        assertEquals(20_000L, h.wallet.balance.value)
    }
}

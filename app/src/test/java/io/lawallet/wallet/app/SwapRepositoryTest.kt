package io.lawallet.wallet.app

import android.app.Application
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.BankOwner
import io.lawallet.wallet.core.api.CurrentUser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SwapRepositoryTest {
    private val h = RepoHarness()
    private val swaps by lazy { SwapRepository(h.context, h.session, h.prefs, h.wallet, h.scope) }

    @After fun tearDown() = h.close()

    private val ana = BankAccount("0000003100010000000001", alias = "Ana.MP", bank = "Mercado Pago", owners = listOf(BankOwner("F", "27123456784", "Ana Pérez")))
    private val beto = BankAccount("0170000000000000000002", alias = "beto.galicia", bank = "Galicia")

    /** Orders [account] through the server, which echoes [holder] back (or "Server Name" when none is sent). */
    private fun order(account: BankAccount, holder: String?, id: String = "s-${account.cvu.takeLast(3)}", hash: String = "hash-$id") = runBlocking {
        h.instance.on("POST /api/wallet/swaps", """{"id":"$id","provider":"wapu","destination":"${account.cvu}","holderName":"${holder ?: "Server Name"}","paymentHash":"$hash","createdAt":"2026-11-2${h.instance.hits.size % 10}T00:00:00Z"}""")
        swaps.create("wapu", 20_000.0, account, holder)
    }

    private fun loggedIn() = runBlocking { h.login(CurrentUser("u1")) }

    @Test fun rememberKeepsOneRecipientPerAccountNewestFirst() {
        loggedIn()
        order(ana, "Ana")
        order(beto, null) // no name typed: the server's holder is saved
        order(ana.copy(alias = "otro.alias"), "Ana P.") // same CVU, alias changed: replaces, moves to the top
        assertEquals(listOf("Ana P." to "otro.alias", "Server Name" to "beto.galicia"), swaps.recipients.value.map { it.holderName to it.account.alias })
    }

    @Test fun rememberDedupesAnUnresolvedAliasByWhatWasTyped() {
        loggedIn()
        // Lookup off: Send passes the typed alias as the account's cvu.
        order(BankAccount("ana.mp"), "Ana", id = "a1")
        order(BankAccount("ana.mp"), "Ana", id = "a2")
        order(BankAccount("ana.mp2"), "Ana", id = "a3")
        assertEquals(listOf("ana.mp2", "ana.mp"), swaps.recipients.value.map { it.account.cvu })
    }

    @Test fun rememberKeepsTwelve() {
        loggedIn()
        repeat(13) { i -> order(BankAccount("%022d".format(i)), "n$i", id = "x$i") }
        assertEquals(12, swaps.recipients.value.size)
        assertEquals("n12", swaps.recipients.value.first().holderName)
        assertEquals(12, h.prefs.cached("bank-recipients", kotlinx.serialization.builtins.ListSerializer(BankRecipient.serializer()))!!.size)
    }

    @Test fun matchingSearchesCvuAliasAndHolder() {
        loggedIn()
        order(ana, "Ana Pérez")
        order(beto, "Beto")
        assertEquals(2, swaps.matching("  ").size)
        assertEquals(listOf("Beto"), swaps.matching("0170").map { it.holderName })
        assertEquals(listOf("Ana Pérez"), swaps.matching("ANA.mp").map { it.holderName }) // alias, any case
        assertEquals(listOf("Beto"), swaps.matching("beto").map { it.holderName })
        assertEquals(emptyList<BankRecipient>(), swaps.matching("zzz"))
    }

    @Test fun recipientForMatchesCvuOrAliasIgnoringCase() {
        loggedIn()
        order(ana, "Ana")
        assertEquals("Ana", swaps.recipientFor("0000003100010000000001")?.holderName)
        assertEquals("Ana", swaps.recipientFor("ana.mp")?.holderName)
        assertNull(swaps.recipientFor("0000003100010000000"))
        assertNull(swaps.recipientFor("mercado pago"))
    }

    @Test fun byHashFindsTheOrderButNeverForAnEmptyHash() {
        loggedIn()
        order(ana, "Ana", id = "s1", hash = "abc")
        order(beto, "Beto", id = "s2", hash = "")
        assertEquals("s1", swaps.byHash("abc")?.id)
        assertNull(swaps.byHash(""))
        assertNull(swaps.byHash("nope"))
        assertEquals("s1", swaps.byId("s1")?.id)
    }
}

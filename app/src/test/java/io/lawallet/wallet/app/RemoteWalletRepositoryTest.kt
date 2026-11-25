package io.lawallet.wallet.app

import android.app.Application
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.plugin.PluginRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Primary rules (pure) and the balance cache (Robolectric: Prefs). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RemoteWalletRepositoryTest {
    private val h = RepoHarness()
    private val repo by lazy { RemoteWalletRepository(h.session, h.prefs, PluginRegistry(h.context, h.http, h.scope, allowHttp = false), h.http, h.wallet) }

    @After fun tearDown() = h.close()

    private fun w(id: String, status: String = "ACTIVE", default: Boolean = false) = RemoteWallet(id, id, status = status, isDefault = default)

    // ---- needsPrimary ----

    @Test fun needsPrimaryUnlessAnActiveWalletIsDefault() {
        assertTrue(needsPrimary(null))
        assertTrue(needsPrimary(emptyList()))
        assertTrue(needsPrimary(listOf(w("a"), w("b"))))
        assertTrue(needsPrimary(listOf(w("a", "DISABLED", default = true), w("b")))) // a paused primary doesn't count
        assertFalse(needsPrimary(listOf(w("a"), w("b", default = true))))
    }

    // ---- nextPrimary: when the primary is paused or deleted, the last-selected wallet that's still active takes over ----

    @Test fun latestActivePastPrimaryWins() {
        val list = listOf(w("a"), w("b", "DISABLED"), w("c"), w("d"))
        // "b" was primary most recently but is paused now, so the next one back ("c") takes over.
        assertEquals("c", nextPrimary(list, listOf("b", "c", "a"))?.id)
    }

    @Test fun noUsableHistoryFallsBackToFirstActive() {
        val list = listOf(w("a", "DEAD"), w("b"), w("c"))
        assertEquals("b", nextPrimary(list, listOf("gone", "a"))?.id)
        assertEquals("b", nextPrimary(list, emptyList())?.id)
    }

    @Test fun nothingActiveMeansNoPrimary() {
        assertNull(nextPrimary(listOf(w("a", "DISABLED"), w("b", "DEAD")), listOf("a")))
    }

    // ---- uniqueName ----

    @Test fun freeNameIsKept() = assertEquals("marce", uniqueName("marce", setOf("Alby")))
    @Test fun takenNameGetsTwo() = assertEquals("marce 2", uniqueName("marce", setOf("marce")))
    @Test fun skipsTakenNumbers() = assertEquals("marce 4", uniqueName("marce", setOf("marce", "marce 2", "marce 3")))
    @Test fun fillsTheFirstGap() = assertEquals("marce 2", uniqueName("marce", setOf("marce", "marce 3")))

    // ---- spendable ----

    @Test fun spendableIsLiveThenTheLastKnownThenNull() = runBlocking {
        h.login(CurrentUser("u1"))
        h.instance.on("GET /api/remote-wallets/w1/balance", """{"balanceSats":1234}""")
        assertEquals(1234L, repo.spendable("w1"))
        assertEquals(1234L, repo.balances["w1"])
        h.instance.on("GET /api/remote-wallets/w1/balance", "", code = 503)
        assertEquals(1234L, repo.spendable("w1")) // unreachable: the last figure
        assertNull(repo.spendable("w2")) // never read
        assertNull(repo.spendable("plugin:off")) // a plugin that isn't installed
    }
}

package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.core.api.RemoteWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the primary is paused or deleted, the last-selected wallet that's still active takes over. */
class NextPrimaryTest {
    private fun w(id: String, status: String = "ACTIVE") = RemoteWallet(id, id, status = status)

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
}

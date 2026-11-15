package io.lawallet.wallet.ui

import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.app.mergeActivity
import io.lawallet.wallet.ui.screens.activityKeys
import io.lawallet.wallet.ui.screens.activityRows
import io.lawallet.wallet.ui.screens.groupRuns
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityGroupingTest {
    private fun tx(desc: String?, type: String = "outgoing", state: String = "settled", n: Int = 0) =
        Tx(type, 1, 0, desc, "h$n", null, null, n.toLong(), state)

    @Test fun consecutiveSameTitleCollapse() {
        val fee = "LNCurl hosting fee"
        val runs = groupRuns(listOf(tx(fee, n = 1), tx(fee, n = 2), tx(fee, n = 3), tx("Coffee", n = 4), tx(fee, n = 5)))
        assertEquals(listOf(3, 1, 1), runs.map { it.size })
    }

    /** A payment between two addresses on one wallet: both sides share hash and time. Repeated keys crash the list. */
    @Test fun selfPaymentRowsGetDistinctKeys() {
        val sent = Tx("outgoing", 21, 0, "zap", "h1", null, 5, 5, "settled")
        val rows = activityRows(mergeActivity(listOf(sent, sent.copy(type = "incoming")), emptyList()), months = true)
        val keys = activityKeys(rows)
        assertEquals(2, rows.count { it is io.lawallet.wallet.ui.screens.ActivityRow.Run })
        assertEquals(keys.size, keys.toSet().size)
        // Even a list nobody merged (the same row twice) can't repeat a key.
        val dup = activityRows(listOf(sent, sent), months = false)
        assertEquals(activityKeys(dup).size, activityKeys(dup).toSet().size)
    }

    @Test fun directionBlankAndPendingNeverGroup() {
        val runs = groupRuns(listOf(
            tx("x", n = 1), tx("x", type = "incoming", n = 2), // different direction
            tx(null, n = 3), tx(null, n = 4),                    // no title
            tx("y", state = "pending", n = 5), tx("y", state = "pending", n = 6),
        ))
        assertEquals(List(6) { 1 }, runs.map { it.size })
    }
}

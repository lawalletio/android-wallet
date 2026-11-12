package io.lawallet.wallet.ui

import io.lawallet.wallet.app.Tx
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

    @Test fun directionBlankAndPendingNeverGroup() {
        val runs = groupRuns(listOf(
            tx("x", n = 1), tx("x", type = "incoming", n = 2), // different direction
            tx(null, n = 3), tx(null, n = 4),                    // no title
            tx("y", state = "pending", n = 5), tx("y", state = "pending", n = 6),
        ))
        assertEquals(List(6) { 1 }, runs.map { it.size })
    }
}

package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.lawallet.wallet.ui.screens.ActivitySearch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

class ActivitySearchFlowTest : FlowTest() {
    private fun rows() = rule.onAllNodes(hasTestTag("tx-row"), useUnmergedTree = true).fetchSemanticsNodes().size

    // The search lives in a process-wide object (the tab bar and the sheet share it).
    @Before fun clearSearch() = ActivitySearch.reset()
    @After fun clearSearchAfter() = ActivitySearch.reset()

    @Test fun searchAndFilterTheActivity() {
        launch()
        waitUntil { rows() == 2 }
        // A third payment, with its own description, arrives live.
        val coffee = buildJsonObject {
            put("type", "incoming"); put("amount", 3_000_000); put("payment_hash", "coffee-hash"); put("invoice", "lnbc1fake")
            put("description", "Coffee beans"); put("created_at", 1_700_000_500); put("settled_at", 1_700_000_600)
        }
        waitUntil { instance.wallet.notify(coffee); rows() == 3 }

        tag("home-activity").performTouchInput { swipeUp(startY = top + 20f, endY = top - 600f) }
        waitForTag("activity-search")
        tag("activity-search").performTextInput("coffee")
        waitUntil { rows() == 1 }
        shot("activity_search")

        // Received off: the only match is incoming.
        tag("activity-filters").performClick()
        waitForTag("filter-received")
        tag("filter-received").performClick()
        waitUntil { rows() == 0 }
    }
}

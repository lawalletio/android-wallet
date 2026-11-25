package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.lawallet.wallet.core.FakeNwcWallet
import io.lawallet.wallet.core.api.RemoteWallet
import org.junit.Test
import org.robolectric.annotation.Config

class HomeFlowTest : FlowTest() {
    private fun rows() = rule.onAllNodes(hasTestTag("tx-row"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun showsTheWalletBalanceAndItsActivity() {
        launch()
        waitForTagText("balance", "21,000")
        waitUntil { rows() == 2 }
        shot("home")
    }

    @Config(qualifiers = "+es")
    @Test fun homeInSpanish() {
        launch()
        waitForTagText("balance", "21.000")
        waitUntil { rows() == 2 }
        shot("home")
    }

    @Test fun anIncomingPaymentShowsUpLive() {
        launch()
        waitForTagText("balance", "21,000")
        instance.wallet.balanceMsat += 5_000_000
        val tx = FakeNwcWallet.tx("incoming", 5_000_000, "live-payment", settled = true)
        // The notification only reaches the app once it has subscribed; re-sending is harmless (same payment hash).
        waitUntil { instance.wallet.notify(tx); rows() == 3 }
        waitForTagText("balance", "26,000")
    }

    @Test fun swipingDownShowsTheAccountsWallets() {
        instance.remoteWallets = listOf(RemoteWallet("w1", "Main", isDefault = true), RemoteWallet("w2", "Savings"))
        launch()
        waitForTagText("balance", "21,000")
        tag("home-lightning").performTouchInput { swipeDown(startY = centerY - 200f, endY = bottom - 10f) }
        waitForTag("home-wallet-Savings")
        waitForTag("home-wallet-Main")
        shot("home_wallets")
    }
}

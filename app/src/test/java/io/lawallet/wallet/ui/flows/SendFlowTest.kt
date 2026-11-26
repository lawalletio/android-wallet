package io.lawallet.wallet.ui.flows

import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.ui.screens.PayNetwork
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.lawallet.wallet.testing.FakeInstance
import io.lawallet.wallet.ui.screens.HomePager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

class SendFlowTest : FlowTest() {
    /** Home (balance loaded) → Send → [destination] typed and submitted. */
    private fun sendTo(destination: String) {
        waitForTagText("balance", "21,000")
        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput(destination)
        tag("recipient-continue").performClick()
    }

    @Test fun payAnInvoiceWithTheConfirmButton() {
        c.prefs.setSlideToPay(false)
        launch()
        sendTo(FakeInstance.invoice(1_000, "Coffee"))

        waitForTag("pay-confirm")
        tag("pay-confirm").assertIsEnabled().performClick()
        waitForText("Payment sent")
        assertTrue("pay_invoice" in instance.wallet.requests)
        // The fake wallet charges 1,000 sats per payment; Home picks up the new balance.
        try {
            waitUntil { c.wallet.balance.value == 20_000L }
        } catch (e: Throwable) {
            // Say where it stopped: the app's balance, and what the wallet was asked.
            throw AssertionError(
                "balance=${c.wallet.balance.value} error=${c.wallet.relayError.value} client=${c.wallet.client.value != null} " +
                    "walletMsat=${instance.wallet.balanceMsat} requests=${instance.wallet.requests}", e,
            )
        }
    }

    @Test fun payAnInvoiceBySwiping() {
        launch()
        sendTo(FakeInstance.invoice(1_000, "Coffee"))

        waitForTag("swipe")
        tag("swipe").performTouchInput { swipeRight(startX = left + 30f, endX = right - 5f) }
        waitForText("Payment sent")
        assertEquals(1, instance.wallet.requests.count { it == "pay_invoice" })
    }

    @Test fun anInvoiceOverTheBalanceCannotBePaid() {
        c.prefs.setSlideToPay(false)
        launch()
        sendTo(FakeInstance.invoice(50_000, "Too much"))

        waitForTag("pay-confirm")
        waitForText("Not enough balance")
        tag("pay-confirm").assertIsNotEnabled()
        assertTrue("pay_invoice" !in instance.wallet.requests)
    }

    @Test fun anAmountlessInvoiceAsksForAnAmountWithinTheBalance() {
        launch()
        sendTo(FakeInstance.invoice(0, "Coffee"))

        waitForTag("amount-continue")
        listOf("3", "0", "0", "0", "0").forEach { tag("key-$it").performClick() }
        waitForText("Not enough balance")
        tag("amount-continue").assertIsNotEnabled()
        shot("send_amount_over_balance")

        tag("amount-max").performClick()
        tag("amount-continue").assertIsEnabled().performClick()
        waitForTag("swipe")
        assertEquals(21_000L, c.flow.send.amountSats)
    }

    @Test fun anInvalidRecipientSaysSo() {
        launch()
        sendTo("not a destination")
        // The parser's own (English) message.
        waitForText("Unsupported recipient", substring = true)
        shot("send_invalid")
    }

    @Config(qualifiers = "+es")
    @Test fun invalidRecipientInSpanish() {
        launch()
        waitForTagText("balance", "21.000")
        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput("not a destination")
        tag("recipient-continue").performClick()
        waitForText("Unsupported recipient", substring = true)
        shot("send_invalid")
    }

    // Paying on the other network: Lightning can't pay a Bitcoin address, so Send says so and offers Bitcoin.
    @Test fun aBitcoinAddressOffersToSwitchToBitcoin() {
        launch()
        waitForTagText("balance", "21,000")
        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput(ADDRESS)
        waitForTag("network-card")
        shot("send_wrong_network")
        tag("network-card-action").performClick()
        // No onchain wallet on this phone: it says so instead of switching.
        waitForText("No onchain wallet on this phone can pay to this address")
    }

    @Test fun scanningABitcoinAddressOnLightningAsksToSwitch() {
        launch()
        waitForTagText("balance", "21,000")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("", ADDRESS))
        tag("nav-scan").performClick()
        waitForText("Paste from clipboard")
        rule.onNode(androidx.compose.ui.test.hasText("Paste from clipboard"), useUnmergedTree = true).performClick()
        waitForTag("network-dialog")
        shot("scan_wrong_network")
        tag("network-switch").performClick()
        waitForText("No onchain wallet on this phone can pay to this address")
        // Home opens on Bitcoin when the user goes back.
        assertEquals(0, HomePager.pending)
    }

    @Test fun scanningASignetAddressFromAMainnetWalletAsksToSwitchWallet() {
        val abandon = ("abandon ".repeat(11) + "about").split(" ")
        fun watch(name: String, chain: Chain) = c.onchain.add(OnchainWallet(name = name, watch = WatchKind.XPUB, chain = chain, descriptors = Descriptors.fromPhrase(abandon, null, ScriptType.P2WPKH, chain)))
        watch("Savings", Chain.MAINNET)
        val test = watch("Test coins", Chain.SIGNET)
        val signet = Descriptors.address(test.descriptors!!.external, 5, Chain.SIGNET)
        HomePager.show(PayNetwork.ONCHAIN.page)
        launch()
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("", signet))
        tag("nav-scan").performClick()
        waitForText("Paste from clipboard")
        rule.onNode(androidx.compose.ui.test.hasText("Paste from clipboard"), useUnmergedTree = true).performClick()
        waitForTag("wallet-dialog")
        waitForText("Savings is on Mainnet. Switch to a wallet on Signet to pay it.")
        shot("scan_wrong_wallet")
        // The selected wallet stays until the user picks the other one.
        assertEquals("Savings", c.onchain.wallets.value.single { it.isPrimary }.name)
        tag("wallet-switch").performClick()
        rule.waitUntil(5_000) { c.onchain.wallets.value.single { it.isPrimary }.id == test.id }
        assertEquals(test.id, c.flow.onchainSend?.walletId)
    }

    private companion object { const val ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" }
}

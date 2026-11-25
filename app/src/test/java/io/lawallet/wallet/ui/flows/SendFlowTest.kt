package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.lawallet.wallet.testing.FakeInstance
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
            // Only CI has missed this so far: say where it stopped.
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
}

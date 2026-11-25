package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

class ReceiveFlowTest : FlowTest() {
    private fun openReceive() {
        waitForTag("home-root")
        waitUntil { c.wallet.client.value != null }
        tag("nav-receive").performClick()
        waitForTag("receive-address")
    }

    @Test fun showsTheLightningAddress() {
        launch()
        openReceive()
        shot("receive_address")
    }

    @Config(qualifiers = "+es")
    @Test fun lightningAddressInSpanish() {
        launch()
        openReceive()
        shot("receive_address")
    }

    @Test fun requestAnAmountShowsTheInvoiceUntilItIsPaid() {
        launch()
        openReceive()
        tag("request-amount").performClick()
        waitForTag("receive-continue")
        listOf("1", "0", "0", "0").forEach { tag("key-$it").performClick() }
        // Hold the invoice screen's lookup_invoice polling (it only runs in the foreground) until the screenshot.
        c.wallet.active.value = false
        tag("receive-continue").performClick()

        waitForTagText("invoice-amount", "1,000")
        assertTrue("make_invoice" in instance.wallet.requests)
        assertEquals(1_000L, c.flow.receive.amountSats)
        shot("receive_invoice")

        // The fake wallet reports every invoice as settled: the screen moves on to the summary.
        c.wallet.active.value = true
        waitForText("Payment received")
        assertTrue("lookup_invoice" in instance.wallet.requests)
    }
}

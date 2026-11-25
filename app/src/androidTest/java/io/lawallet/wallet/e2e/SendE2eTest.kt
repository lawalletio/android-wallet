package io.lawallet.wallet.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.R
import io.lawallet.wallet.testing.FakeInstance
import io.lawallet.wallet.testing.TestContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendE2eTest : E2eTest() {
    private val lnurl = LnurlServer().start()

    /** `alice@pay.test` resolves to [lnurl] (Lightning addresses are https-only). */
    override fun http() = TestContainer.localOnlyHttp().newBuilder().apply { interceptors().add(0, lnurl.redirect("pay.test")) }.build()

    @After fun stopLnurl() = lnurl.close()

    /** Home (balance loaded) → Send → [destination] → its preview, paid with the confirm button → back on Home. */
    private fun pay(destination: String) {
        c.prefs.setSlideToPay(false)
        launch()
        waitForTagText("balance", sats(21_000))
        waitUntil { txRows() == 2 }
        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput(destination)
        tag("recipient-continue").performClick()
        waitForTag("pay-confirm")
        tag("pay-confirm").assertIsEnabled().performClick()
        waitForText(string(R.string.send_summary_title))
        assertEquals(1, instance.wallet.requests.count { it == "pay_invoice" })
        tag("done").performClick()

        // The fake wallet charges 1,000 sats a payment and reports it as a payment_sent notification.
        waitForTag("home-root")
        waitForTagText("balance", sats(20_000))
        waitUntil { txRows() == 3 }
    }

    @Test fun payABolt11Invoice() {
        pay(FakeInstance.invoice(1_000, "Coffee"))
    }

    @Test fun payALightningAddress() {
        pay("alice@pay.test")
        assertTrue(lnurl.server.requestCount >= 2) // metadata + callback
    }
}

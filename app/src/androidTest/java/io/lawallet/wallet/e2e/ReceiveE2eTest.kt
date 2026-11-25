package io.lawallet.wallet.e2e

import androidx.compose.ui.test.performClick
import io.lawallet.wallet.R
import io.lawallet.wallet.app.PaymentNotice
import io.lawallet.wallet.core.FakeNwcWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReceiveE2eTest : E2eTest() {
    // regression: 9a4eb0e fix(notifications): one notification per incoming payment (#19)
    @Test fun regression_9a4eb0e_anInvoicePaidShowsExactlyOneIncomingNotice() {
        launch()
        waitForTag("home-root")
        waitUntil { c.wallet.client.value != null }
        tag("nav-receive").performClick()
        waitForTag("request-amount")
        tag("request-amount").performClick()
        waitForTag("receive-continue")
        listOf("1", "0", "0", "0").forEach { tag("key-$it").performClick() }
        // Hold the screen's lookup_invoice polling (the fake reports every invoice settled): the payment arrives as a
        // wallet notification only, the path #19 duplicated.
        c.wallet.active.value = false
        val notices = CopyOnWriteArrayList<PaymentNotice>()
        val watch = CoroutineScope(Dispatchers.Default)
        watch.launch(start = CoroutineStart.UNDISPATCHED) { c.wallet.notices.collect { notices += it } }
        try {
            tag("receive-continue").performClick()
            waitForTagText("invoice-amount", sats(1_000))
            assertTrue("make_invoice" in instance.wallet.requests)

            // make_invoice's hash in the fake. Re-sent until the app has subscribed: every extra copy is a duplicate.
            val paid = FakeNwcWallet.tx("incoming", 1_000_000, "hash-make", settled = true)
            waitUntil { instance.wallet.notify(paid); notices.any { it.tx.paymentHash == "hash-make" } }
            waitForText(string(R.string.receive_summary_title))

            // More duplicates, then a different payment: once that one is noticed, every copy before it was handled.
            repeat(2) { instance.wallet.notify(paid) }
            instance.wallet.notify(FakeNwcWallet.tx("incoming", 2_000_000, "sentinel", settled = true))
            waitUntil { notices.any { it.tx.paymentHash == "sentinel" } }
            assertEquals(1, notices.count { it.tx.paymentHash == "hash-make" })
        } finally {
            watch.cancel()
        }
    }
}

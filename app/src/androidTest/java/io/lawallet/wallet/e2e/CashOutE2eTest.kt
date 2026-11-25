package io.lawallet.wallet.e2e

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.BankOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CashOutE2eTest : E2eTest() {
    /** Made up: a test CBU and alias, not anyone's account. */
    private val account = BankAccount("0000000000000000000042", alias = "test.alias.ok", bank = "Test Bank", owners = listOf(BankOwner("F", "00-00000000-0", "Ana Test")))

    @Test fun bankLookupConfirmPendingThenDelivered() {
        instance.bankAccounts = mapOf("test.alias.ok" to account)
        instance.completeOnPaid = false
        c.prefs.setSlideToPay(false)
        launch()
        waitForTagText("balance", sats(21_000))
        waitUntil { c.session.settings.value?.bankLookupEnabled == true }

        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput("test.alias.ok")
        waitForText("Ana Test")
        assertTrue(instance.paths().contains("GET /api/wallet/alias/test.alias.ok"))
        tag("bank-row").performClick()

        waitForTag("cashout-amount-continue")
        listOf("5", "0", "0", "0").forEach { tag("key-$it").performClick() }
        tag("cashout-amount-continue").performClick()
        waitForTag("provider-bullbitcoin")
        tag("provider-bullbitcoin").performClick()
        waitForTag("cashout-pay")
        val order = instance.swaps.single()
        assertEquals(account.cvu, order.destination)
        assertEquals(5_000L, order.amountSats)
        tag("cashout-pay").performClick()

        // Paid, the exchange still moving the pesos.
        waitForTagText("cashout-status", string(R.string.cashOut_status_PENDING))
        assertTrue("pay_invoice" in instance.wallet.requests)
        assertNotNull(instance.swaps.single().paidAt)

        // The exchange delivers: the watched order's poll picks it up.
        instance.swaps = instance.swaps.map { it.copy(status = "COMPLETED", updatedAt = java.time.Instant.now().toString()) }
        waitForTagText("cashout-status", string(R.string.cashOut_status_COMPLETED))
    }
}

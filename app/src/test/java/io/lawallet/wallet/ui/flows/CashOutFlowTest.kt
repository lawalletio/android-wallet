package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.BankOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

class CashOutFlowTest : FlowTest() {
    /** Made up: a test CBU and alias, not anyone's account. */
    private val account = BankAccount("0000000000000000000042", alias = "test.alias.ok", bank = "Test Bank", owners = listOf(BankOwner("F", "00-00000000-0", "Ana Test")))

    private fun lookUpTheAlias() {
        instance.bankAccounts = mapOf("test.alias.ok" to account)
        c.prefs.setSlideToPay(false)
        launch()
        waitForTagText("balance", if (java.util.Locale.getDefault().language == "es") "21.000" else "21,000")
        waitUntil { c.session.settings.value?.bankLookupEnabled == true }
        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput("test.alias.ok")
        waitForText("Ana Test")
    }

    @Test fun lookUpABankAccountAndSendItPesos() {
        lookUpTheAlias()
        assertTrue(instance.paths().contains("GET /api/wallet/alias/test.alias.ok"))
        shot("cashout_bank_lookup")

        tag("bank-row").performClick()
        waitForTag("cashout-amount-continue")
        listOf("5", "0", "0", "0").forEach { tag("key-$it").performClick() }
        waitForTagText("cashout-amount", "5,000")
        shot("cashout_amount")
        tag("cashout-amount-continue").performClick()

        // The exchange's quote: 1 ARS = 1 sat at the fake's rate.
        waitForTag("provider-bullbitcoin")
        tag("provider-bullbitcoin").performClick()
        waitForTag("cashout-pay")
        val order = instance.swaps.single()
        assertEquals(account.cvu, order.destination)
        assertEquals(5_000L, order.amountSats)
        tag("cashout-pay").performClick()

        waitForTag("cashout-status")
        waitForText("Pesos sent")
        assertTrue("pay_invoice" in instance.wallet.requests)
        assertTrue(instance.paths().contains("POST /api/wallet/swaps/${order.id}/paid"))
    }

    @Config(qualifiers = "+es")
    @Test fun bankLookupInSpanish() {
        lookUpTheAlias()
        shot("cashout_bank_lookup")
    }
}

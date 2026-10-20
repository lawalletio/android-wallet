package io.lawallet.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.SwipeButton
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.screens.SecretKeyReveal
import io.lawallet.wallet.ui.theme.LaWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun keypadDrivesTheAmountInEveryCurrencyMode() {
        rule.setContent {
            LaWalletTheme {
                var code by remember { mutableStateOf("SAT") }
                var raw by remember { mutableStateOf("0") }
                Column {
                    AmountDisplay(keypadDisplay(raw, code), Currencies.unitLabel(code))
                    CurrencyToggle(listOf("SAT", "USD"), code, { code = it; raw = "0" })
                    AmountKeypad(raw, { raw = it }, Currencies.keypadMode(code), Modifier.height(400.dp))
                }
            }
        }
        listOf("1", "2", "0", "0").forEach { rule.onNodeWithTag("key-$it").performClick() }
        rule.onNodeWithTag("amount-value").assertTextEquals("1,200")
        rule.onNodeWithTag("key-backspace").performClick()
        rule.onNodeWithTag("amount-value").assertTextEquals("120")

        rule.onNodeWithTag("currency-USD").performClick()
        listOf("1", "2", "3").forEach { rule.onNodeWithTag("key-$it").performClick() }
        rule.onNodeWithTag("amount-value").assertTextEquals("1.23") // cash-register fiat entry
        rule.onNodeWithTag("key-00").performClick()
        rule.onNodeWithTag("amount-value").assertTextEquals("123.00")
    }

    @Test fun createAccountRequiresConfirmingTheBackup() {
        val nsec = Keys.generate().nsec
        rule.setContent {
            LaWalletTheme {
                var confirmed by remember { mutableStateOf(false) }
                Column {
                    SecretKeyReveal(nsec, confirmed, { confirmed = it })
                    LwButton("Continue", {}, Modifier.testTag("continue"), BtnVariant.Theme, enabled = confirmed)
                }
            }
        }
        rule.onNodeWithTag("nsec").assertTextEquals(nsec)
        rule.onNodeWithTag("continue").assertIsNotEnabled()
        rule.onNodeWithTag("confirm-saved").performClick()
        rule.onNodeWithTag("continue").assertIsEnabled()
    }

    @Test fun swipeToPayConfirmsOnlyAfterASwipe() {
        var paid = 0
        rule.setContent { LaWalletTheme { SwipeButton("Swipe to pay", { paid++ }, loading = false, done = false) } }
        rule.onNodeWithText("Swipe to pay").performClick()
        assertEquals(0, paid)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight(startX = left + 30f, endX = right - 5f) }
        rule.waitUntil(5_000) { paid > 0 }
        assertTrue(paid == 1)
    }

    @Test fun buttonVariantsRender() {
        rule.setContent { LaWalletTheme { Column { BtnVariant.entries.forEach { LwButton(it.name, {}, variant = it) }; Txt("ok") } } }
        BtnVariant.entries.forEach { rule.onNodeWithText(it.name).assertExists() }
    }
}

@RunWith(AndroidJUnit4::class)
class WalletComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun receiptKeepsProofHiddenUntilRevealed() {
        rule.setContent {
            LaWalletTheme {
                io.lawallet.wallet.ui.components.PaymentReceiptCard(
                    "Payment sent", "settled", 2_100, "SAT", listOf("SAT", "BTC"), null, {},
                    rows = listOf("Note" to "coffee"), proofs = listOf("Preimage" to "ab".repeat(32), "Payment hash" to null),
                )
            }
        }
        rule.onNodeWithTag("receipt-amount").assertTextEquals("2,100")
        rule.onNodeWithTag("proof-Preimage").assertTextEquals("•".repeat(24))
        rule.onNodeWithTag("proof-toggle-Preimage").performClick()
        rule.onNodeWithTag("proof-Preimage").assertTextEquals("ab".repeat(32))
        rule.onNodeWithText("Not provided by wallet").assertExists() // a missing proof has no reveal control
    }

    @Test fun tabBarRoutesEachTarget() {
        val taps = mutableListOf<String>()
        rule.setContent {
            LaWalletTheme {
                io.lawallet.wallet.ui.components.NavTabBar("home", { taps += "home" }, { taps += "settings" }, { taps += "scan" }, haze = null)
            }
        }
        rule.onNodeWithTag("nav-scan").performClick()
        rule.onNodeWithTag("nav-Settings").performClick()
        rule.onNodeWithTag("nav-Home").performClick()
        assertEquals(listOf("scan", "settings", "home"), taps)
    }

    @Test fun iconButtonsKeepA48dpTouchTarget() {
        rule.setContent {
            LaWalletTheme {
                io.lawallet.wallet.ui.components.GlassIconButton(com.composables.icons.lucide.R.drawable.lucide_ic_x, {}, Modifier.testTag("close"), size = 40.dp)
            }
        }
        rule.onNodeWithTag("close").assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
    }

    @Test fun paymentNoticeShowsDirectionAndSignedAmount() {
        rule.setContent { LaWalletTheme { io.lawallet.wallet.ui.components.PaymentNoticePill("payment_sent", 2_100, null) } }
        rule.onNodeWithText("Sent").assertExists()
        rule.onNodeWithText("−2,100").assertExists()
    }
}

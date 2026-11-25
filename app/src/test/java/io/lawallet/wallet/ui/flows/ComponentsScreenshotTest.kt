package io.lawallet.wallet.ui.flows

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.NavTabBar
import io.lawallet.wallet.ui.components.PaymentNoticePill
import io.lawallet.wallet.ui.components.PaymentReceiptCard
import io.lawallet.wallet.ui.components.SwipeButton
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.screens.SecretKeyReveal
import io.lawallet.wallet.ui.theme.LaWalletTheme
import io.lawallet.wallet.ui.theme.Lw
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Goldens for the components app/src/androidTest/…/ComponentsTest.kt drives on a device. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class ComponentsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { LaWalletTheme { Column(Modifier.background(Lw.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/component_$name.png")
    }

    @Test fun keypad() = shot("keypad") {
        AmountDisplay(keypadDisplay("1200", "SAT"), Currencies.unitLabel("SAT"))
        CurrencyToggle(listOf("SAT", "USD"), "SAT", {})
        AmountKeypad("1200", {}, Currencies.keypadMode("SAT"), Modifier.height(400.dp))
    }

    // A fixed throwaway key, so the golden doesn't change.
    @Test fun secretKeyReveal() = shot("secret_key_reveal") { SecretKeyReveal(Keys.fromHex("11".repeat(32)).nsec, true, {}) }

    @Test fun swipeButton() = shot("swipe_button") { SwipeButton("Swipe to pay", {}, loading = false, done = false) }

    @Test fun buttonVariants() = shot("button_variants") { BtnVariant.entries.forEach { LwButton(it.name, {}, variant = it) } }

    @Test fun paymentReceipt() = shot("payment_receipt") {
        PaymentReceiptCard(
            "Payment sent", "settled", 2_100, "SAT", listOf("SAT", "BTC"), null, {},
            rows = listOf("Note" to "coffee"), proofs = listOf("Preimage" to "ab".repeat(32), "Payment hash" to null),
        )
    }

    @Test fun tabBar() = shot("tab_bar") { NavTabBar({}, {}, {}, haze = null) }

    @Test fun iconButtonAndNotice() = shot("icon_button_notice") {
        GlassIconButton(com.composables.icons.lucide.R.drawable.lucide_ic_x, {}, size = 40.dp)
        PaymentNoticePill("payment_sent", 2_100, null)
        PaymentNoticePill("payment_received", 21_000, null)
    }
}

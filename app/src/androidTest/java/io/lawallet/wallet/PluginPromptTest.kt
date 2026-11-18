package io.lawallet.wallet

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.lawallet.wallet.core.plugin.PromptRequest
import io.lawallet.wallet.core.plugin.PromptSpec
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.screens.PluginPromptDialog
import io.lawallet.wallet.ui.theme.LaWalletTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A plugin's `host.prompt()` (an emailed code before a payment): what it shows, and what it answers. */
@RunWith(AndroidJUnit4::class)
class PluginPromptTest {
    @get:Rule val rule = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LaWalletApp
    private val spec = PromptSpec("Example emailed you a code", "Enter the code Example sent to your email to confirm this payment.", "code", "Código inválido", resend = true)

    private fun show(r: PromptRequest) = rule.setContent {
        CompositionLocalProvider(LocalContainer provides app.container, LocalNav provides rememberNavController()) {
            LaWalletTheme { PluginPromptDialog(r) }
        }
    }

    @Test fun aCodeAnswersThePlugin() {
        val r = PromptRequest("io.example.wallet", "Example", spec, CompletableDeferred())
        show(r)
        rule.onNodeWithText(spec.title).assertIsDisplayed()
        rule.onNodeWithText("Código inválido").assertIsDisplayed()
        rule.onNodeWithTag("plugin-prompt-continue").assertIsNotEnabled()
        rule.onNodeWithTag("plugin-prompt-input").performTextInput("12 34-56")
        // For review: the dialog as the user sees it (pulled from the app's cache dir).
        val shot: Bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(app.cacheDir, "plugin-prompt.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithTag("plugin-prompt-continue").performClick()
        assertEquals("123456", r.result.getCompleted()) // a code keeps letters and digits only
    }

    @Test fun resendAsksForANewCode() {
        val r = PromptRequest("io.example.wallet", "Example", spec, CompletableDeferred())
        show(r)
        rule.onNodeWithTag("plugin-prompt-resend").performClick()
        assertEquals(PromptSpec.RESEND, r.result.getCompleted())
    }
}

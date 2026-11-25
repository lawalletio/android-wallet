package io.lawallet.wallet.ui.flows

import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.store.LockPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

class SettingsFlowTest : FlowTest() {
    private fun openSettings() {
        launch()
        waitForTag("home-settings")
        tag("home-settings").performClick()
        waitForTag("settings-locking")
    }

    /** Settings shows the instance's host, whose port changes every run: allow that much difference. */
    private fun settingsShot() {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/settings_${Locale.getDefault().language}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)),
        )
    }

    /** Whether the device has a screen lock. */
    private fun biometrics(available: Boolean) =
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).run {
            setCanAuthenticate(available)
            org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(available)
            setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        }

    @Test fun settingsScreen() {
        openSettings()
        settingsShot()
    }

    @Config(qualifiers = "+es")
    @Test fun settingsInSpanish() {
        openSettings()
        settingsShot()
    }

    @Test fun paymentToggles() {
        openSettings()
        tag("settings-payments").performClick()
        waitForTag("slide-to-pay")
        tag("slide-to-pay").performClick()
        waitUntil { !c.prefs.slideToPay.value }
        tag("nfc-autopay").performClick()
        waitUntil { c.prefs.nfcAutoPay.value.enabled }
        shot("settings_payments")
        tag("slide-to-pay").performClick()
        waitUntil { c.prefs.slideToPay.value }
    }

    @Test fun lockPaymentsFromAnAmount() {
        biometrics(true)
        openSettings()
        tag("settings-locking").performClick()
        waitForTag("lock-payments")
        tag("lock-payments").performClick()
        waitForTag("lock-min-amount")
        tag("lock-min-amount").performTextReplacement("5000")
        waitUntil { c.prefs.lock.value == LockPrefs(payments = true, paymentsMinSats = 5_000) }
        tag("lock-sensitive").performClick()
        waitUntil { c.prefs.lock.value.sensitive }
        shot("locking")
        assertTrue(c.lock.paymentNeedsAuth(5_000))
        assertFalse(c.lock.paymentNeedsAuth(4_999))
    }

    @Test fun removeTheWalletLogsOut() {
        openSettings()
        // Its own click action: scrolled into view, the row can sit under the floating tab bar.
        tag("settings-remove").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitForTag("confirm-remove")
        tag("confirm-remove").performClick()
        waitUntil { c.session.auth.value == AuthState.LoggedOut }
        waitForTag("landing-create")
        assertEquals(null, c.prefs.signerSecretBlob)
    }
}

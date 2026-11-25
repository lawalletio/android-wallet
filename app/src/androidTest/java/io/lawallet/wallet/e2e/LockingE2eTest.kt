package io.lawallet.wallet.e2e

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppLock
import io.lawallet.wallet.core.store.LockPrefs
import io.lawallet.wallet.testing.FakeInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Settings → Locking on a device without a screen lock (the test emulator has none). The device-lock prompt itself
 * (BiometricPrompt passing or failing) isn't covered: an emulator can't pass it without enrolled credentials, and
 * enrolling one would change the device for every app. Robolectric's SettingsFlowTest covers the toggles with a lock.
 */
class LockingE2eTest : E2eTest() {
    @Test fun withoutAScreenLockPaymentsCannotBeLocked() {
        assumeFalse("needs a device without a screen lock", AppLock.available(app))
        launch()
        waitForTag("home-settings")
        tag("home-settings").performClick()
        waitForTag("settings-locking")
        tag("settings-locking").performClick()
        waitForTag("lock-payments")
        // The switch says why (a toast) and stays off: no lock the user couldn't get past.
        tag("lock-payments").performClick()
        assertEquals(LockPrefs(), c.prefs.lock.value)
        assertFalse(has("lock-min-amount"))
    }

    @Test fun aLockedPaymentStillGoesThroughWhenTheDeviceHasNoLockToAskFor() {
        assumeFalse("needs a device without a screen lock", AppLock.available(app))
        // As left behind by a screen lock that was later removed: payments from 500 sats ask for it.
        c.prefs.setLock(LockPrefs(payments = true, paymentsMinSats = 500))
        c.prefs.setSlideToPay(false)
        launch()
        waitForTagText("balance", sats(21_000))
        assertTrue(c.lock.paymentNeedsAuth(1_000))

        tag("nav-send").performClick()
        waitForTag("recipient-input")
        tag("recipient-input").performTextInput(FakeInstance.invoice(1_000, "Locked"))
        tag("recipient-continue").performClick()
        waitForTag("pay-confirm")
        tag("pay-confirm").performClick()
        waitForText(string(R.string.send_summary_title))
        assertTrue("pay_invoice" in instance.wallet.requests)
    }
}

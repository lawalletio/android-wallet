package io.lawallet.wallet.e2e

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.lawallet.wallet.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class WithdrawE2eTest : E2eTest() {
    // The scanner asks for the camera on open; the system dialog would cover the Paste button.
    @get:Rule(order = 2) val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val lnurl = LnurlServer().start()

    @After fun stopLnurl() = lnurl.close()

    // regression: 02dfa1a fix(withdraw): return to the originating screen after a sweep
    @Test fun regression_02dfa1a_collectingASweepReturnsToTheScreenItStartedFrom() {
        launch()
        waitForTag("home-settings")
        waitUntil { c.wallet.client.value != null }
        tag("home-settings").performClick()
        waitForTag("settings-locking")

        // Settings → Scan → paste a withdraw voucher (the emulator's camera can't see a QR).
        tag("nav-scan").performClick()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("voucher", LnurlServer.lnurl(lnurl.withdrawUrl)))
        }
        waitForText(string(R.string.scan_paste))
        button(string(R.string.scan_paste)).performClick()

        waitForTag("withdraw-cta")
        tag("withdraw-cta").performClick()
        waitForTag("withdraw-phase-won")
        assertEquals(1, lnurl.withdrawn.size)
        button(string(R.string.withdraw_celebration_collect)).performClick()

        // Back on Settings (the withdraw screen and the scanner both gone), not Home.
        waitUntil { has("settings-locking") && !has("withdraw-phase-won") && !shows(string(R.string.scan_paste)) }
        assertFalse(has("home-root"))
    }
}

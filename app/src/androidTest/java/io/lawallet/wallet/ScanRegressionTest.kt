package io.lawallet.wallet

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.rule.GrantPermissionRule
import io.lawallet.wallet.e2e.E2eTest
import org.junit.Rule
import org.junit.Test

class ScanRegressionTest : E2eTest() {
    @get:Rule(order = 2) val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun scanOpen() = shows(string(R.string.scan_paste))

    // regression: 4e7cb34 fix(scan): stop crash when the QR scanner opens twice
    @Test fun regression_4e7cb34_openingTheScannerTwiceDoesNotCrash() {
        launch()
        waitForTag("nav-scan")

        // A double tap: both taps land before the screen changes. One scanner on the stack, not two.
        tag("nav-scan").performTouchInput { click(); advanceEventTime(60); click() }
        // The camera feed (and ML Kit with it) starts once the screen has settled.
        waitUntil { hasView(PreviewView::class.java) }
        back()
        waitUntil { !scanOpen() && has("home-root") }

        // Opened again in the same process: ML Kit is initialized once, the camera feed comes up again.
        tag("nav-scan").performClick()
        waitUntil { scanOpen() && hasView(PreviewView::class.java) }
        back()
        waitUntil { !scanOpen() && has("home-root") }
    }
}

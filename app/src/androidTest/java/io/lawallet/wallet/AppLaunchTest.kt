package io.lawallet.wallet

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test: the real activity boots into either onboarding (logged out) or the wallet home. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    // A fresh install asks for notifications on first launch; that dialog would cover the activity.
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesIntoLandingOrHome() {
        rule.waitUntil(60_000) {
            rule.onAllNodesWithTag("landing-create").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("home-receive").fetchSemanticsNodes().isNotEmpty()
        }
    }
}

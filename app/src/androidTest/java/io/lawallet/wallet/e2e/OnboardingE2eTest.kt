package io.lawallet.wallet.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.testing.FakeInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingE2eTest : E2eTest() {
    @Test fun createAccountConfirmBackupThenHome() {
        launch(loggedIn = false)
        waitForTag("landing-create")
        tag("landing-create").performClick()
        waitForTag("signup-random")
        tag("signup-random").performClick()
        waitForTag("create-continue")
        tag("create-continue").assertIsNotEnabled()
        tag("confirm-saved").performClick()
        tag("create-continue").assertIsEnabled().performClick()

        waitForTag("welcome-enter")
        assertEquals(AuthState.LoggedIn, c.session.auth.value)
        assertTrue(instance.paths().contains("POST /api/jwt"))
        tag("welcome-enter").performClick()
        waitForTag("home-root")
    }

    @Test fun logInWithAnNsecShowsTheWalletBalance() {
        instance.wallet.balanceMsat = 42_000_000
        val keys = Keys.fromHex(FakeInstance.throwawaySecret())
        launch(loggedIn = false)
        waitForTag("landing-login")
        tag("landing-login").performClick()
        waitForTag("login-nostr")
        tag("login-nostr").performClick()
        waitForTag("nsec-input")
        tag("nsec-input").performTextInput(keys.nsec)
        tag("nsec-submit").performClick()

        waitForTag("home-root")
        waitForTagText("balance", sats(42_000))
        assertEquals(keys.secretHex, c.session.secretHex())
        assertTrue("get_balance" in instance.wallet.requests)
    }
}

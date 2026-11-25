package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.testing.FakeInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

class OnboardingFlowTest : FlowTest() {
    @Test fun createAccountAsksToConfirmTheBackupThenWelcomes() {
        launch(loggedIn = false)
        waitForTag("landing-create")
        shot("landing")
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

    @Test fun logInWithAnNsec() {
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
        assertEquals(keys.secretHex, c.session.secretHex())
        assertEquals("nsec", c.prefs.loginMethod)
    }

    @Test fun aKeyThatIsNotOneStaysOnTheLoginScreen() {
        launch(loggedIn = false)
        waitForTag("landing-login")
        tag("landing-login").performClick()
        waitForTag("login-nostr")
        tag("login-nostr").performClick()
        waitForTag("nsec-input")
        tag("nsec-input").performTextInput("nsec1notakey")
        tag("nsec-submit").performClick()

        waitForText("Failed to login")
        assertNull(c.session.secretHex())
        assertTrue(instance.paths().none { it == "POST /api/jwt" })
        shot("login_nostr_error")
    }

    @Config(qualifiers = "+es")
    @Test fun landingInSpanish() {
        launch(loggedIn = false)
        waitForTag("landing-create")
        shot("landing")
    }
}

package io.lawallet.wallet.ui.flows

import androidx.compose.ui.test.performClick
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip19
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kind 0 comes from public relays (Profiles.RELAYS, through the process-wide RelayPool), which tests never reach and
 * whose failure timing depends on what else ran in the JVM: these tests only check what doesn't come from them, and
 * take no screenshot.
 */
class ProfileFlowTest : FlowTest() {
    private fun openProfile() {
        waitForTag("avatar")
        tag("avatar").performClick()
        waitForTag("profile-logout")
    }

    private fun npubChip(pubkey: String) = Nip19.npub(pubkey).let { it.take(14) + "…" + it.takeLast(6) }

    @Test fun showsTheAccountAndLogsOut() {
        launch()
        openProfile()
        waitForText("satoshi@localhost")
        waitForText(npubChip(Keys.fromHex(TEST_SECRET).pubkey))

        tag("profile-logout").performClick()
        waitForTag("modal-action")
        tag("modal-action").performClick()
        waitForTag("landing-create")
        assertEquals(AuthState.LoggedOut, c.session.auth.value)
    }

    // regression: 143eee5 fix(profile): passkey logins show the account's primary profile
    @Test fun regression_143eee5_aSecondaryKeyShowsThePrimaryAccountsKey() {
        val primary = Keys.generate().pubkey
        instance.accountPubkey = primary
        launch()
        openProfile()
        waitForText(npubChip(primary))
    }
}

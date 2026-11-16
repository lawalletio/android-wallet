package io.lawallet.wallet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.lawallet.wallet.core.api.Poll
import io.lawallet.wallet.core.api.PollOption
import io.lawallet.wallet.core.api.PollState
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.screens.PollScreen
import io.lawallet.wallet.ui.screens.pollCacheKey
import io.lawallet.wallet.ui.theme.LaWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ranking poll from the cache (the slug doesn't exist on any server): reorder, skip, add a custom answer, add back. */
@RunWith(AndroidJUnit4::class)
class PollScreenTest {
    @get:Rule val rule = createComposeRule()
    private val slug = "ui-test-poll"

    @Test fun ranksSkipsAndAddsACustomAnswer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LaWalletApp
        val poll = Poll(slug = slug, title = "USDT tech", options = listOf(PollOption("tron", "Tron"), PollOption("polygon", "Polygon"), PollOption("liquid", "Liquid")), allowCustom = true)
        app.container.prefs.setCached(pollCacheKey(slug), PollState.serializer(), PollState(poll))
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides app.container, LocalNav provides rememberNavController()) {
                LaWalletTheme { PollScreen(slug) }
            }
        }
        rule.onNodeWithTag("poll-row-tron").assert(hasAnyDescendant(hasText("1")))
        rule.onNodeWithTag("poll-submit").assertIsEnabled() // never answered: the default order is a valid answer

        // The accessibility action does what a drag does.
        val down = app.getString(R.string.poll_move_down)
        val actions = rule.onNodeWithTag("poll-row-tron").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnUiThread { actions.first { it.label == down }.action() }
        rule.onNodeWithTag("poll-row-tron").assert(hasAnyDescendant(hasText("2")))
        rule.onNodeWithTag("poll-row-polygon").assert(hasAnyDescendant(hasText("1")))

        // ✕ skips an option: it leaves the ranking for the crossed-out "Skipped" list.
        rule.onNodeWithTag("poll-remove-liquid").performClick()
        rule.onNodeWithTag("poll-row-liquid").assertDoesNotExist()
        rule.onNodeWithTag("poll-skipped-liquid").assertExists()

        rule.onNodeWithTag("poll-add-custom").performClick()
        rule.onNodeWithTag("modal-action").assertIsNotEnabled()
        rule.onNodeWithTag("poll-custom-field").performTextInput("Ethereum")
        rule.onNodeWithTag("modal-action").performClick()
        rule.onNodeWithTag("poll-row-custom:Ethereum").assert(hasAnyDescendant(hasText("3")))
        rule.onNodeWithTag("poll-add-custom").assertDoesNotExist() // one custom answer at most

        // A tap puts a skipped option back, last.
        rule.onNodeWithTag("poll-skipped-liquid").performClick()
        rule.onNodeWithTag("poll-row-liquid").assert(hasAnyDescendant(hasText("4")))
    }
}

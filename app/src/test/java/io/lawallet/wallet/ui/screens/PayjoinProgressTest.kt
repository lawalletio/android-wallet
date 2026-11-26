package io.lawallet.wallet.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.lawallet.wallet.app.PayjoinRun
import io.lawallet.wallet.app.PayjoinRun.Step
import io.lawallet.wallet.core.btc.Payjoin
import io.lawallet.wallet.ui.theme.LaWalletTheme
import io.lawallet.wallet.ui.theme.Lw
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Goldens for the payjoin screen at each point a payment goes through. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class PayjoinProgressTest {
    @get:Rule val rule = createComposeRule()

    private val original = Payjoin.Flow(ours = listOf(100_000), theirs = emptyList(), payment = 30_000, change = 69_719, fee = 281)
    private val joined = Payjoin.Flow(ours = listOf(100_000), theirs = listOf(50_000), payment = 79_990, change = 69_583, fee = 427)

    private fun shot(name: String, run: PayjoinRun) {
        // The screen animates while a payjoin runs: step the clock by hand, or the rule never goes idle.
        rule.mainClock.autoAdvance = false
        rule.setContent { LaWalletTheme { Box(Modifier.background(Lw.Background)) { PayjoinProgress(run, {}, {}) } } }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onRoot().captureRoboImage("src/test/screenshots/payjoin_$name.png")
    }

    @Test fun waiting() = shot("waiting", PayjoinRun(Step.WAIT, original, relay = "payjoin.lab.vinteum.org", directory = "pj.bullbitcoin.com", deadline = System.currentTimeMillis() + 42_000))

    @Test fun broadcasting() = shot("broadcasting", PayjoinRun(Step.BROADCAST, joined, relay = "payjoin.lab.vinteum.org", directory = "pj.bullbitcoin.com", extraFee = 136))

    @Test fun joined() = shot("joined", PayjoinRun(Step.DONE, joined, relay = "payjoin.lab.vinteum.org", directory = "pj.bullbitcoin.com", txid = "ab".repeat(32), joined = true, extraFee = 136))

    @Test fun fallback() = shot("fallback", PayjoinRun(Step.DONE, original, relay = "payjoin.lab.vinteum.org", directory = "pj.bullbitcoin.com", txid = "cd".repeat(32), joined = false, failedAt = Step.WAIT, why = PayjoinRun.Why.TIMEOUT))
}

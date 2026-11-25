package io.lawallet.wallet.ui.flows

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import android.content.ComponentName
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.testing.FakeInstance
import io.lawallet.wallet.testing.TestContainer
import io.lawallet.wallet.testing.loginThrowaway
import io.lawallet.wallet.ui.LaWalletRoot
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * The whole app (LaWalletRoot: its NavHost, LocalContainer and LocalNav) on Robolectric, against a [FakeInstance].
 * Screenshots: `./gradlew recordRoborazziDebug` writes them to app/src/test/screenshots, `verifyRoborazziDebug` diffs.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
abstract class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<FragmentActivity>? = null
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var instance: FakeInstance
    protected lateinit var c: AppContainer

    @Before fun startInstance() {
        // Dates in activity rows render the same everywhere.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        instance = FakeInstance().start()
        c = TestContainer.create(context, instance)
        // No image loading at all (avatars, domain logos): nothing leaves the machine and screenshots don't wait on it.
        coil3.SingletonImageLoader.setUnsafe(
            coil3.ImageLoader.Builder(context).components {
                add(coil3.intercept.Interceptor { chain -> coil3.request.ErrorResult(null, chain.request, java.io.IOException("no images in tests")) })
            }.build(),
        )
    }

    @After fun stopInstance() {
        scenario?.close()
        c.scope.cancel()
        instance.close()
    }

    /** Shows the app; [loggedIn] first logs in with a fixed throwaway key (the same avatar in every screenshot). */
    protected fun launch(loggedIn: Boolean = true) {
        if (loggedIn) runBlocking { c.loginThrowaway(TEST_SECRET) }
        // In the foreground, as LaWalletApp's process observer sets it: balance polling runs.
        c.wallet.active.value = true
        // Screens prompt for the device lock through a FragmentActivity, as MainActivity is.
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, FragmentActivity::class.java))
        scenario = ActivityScenario.launch(FragmentActivity::class.java).onActivity {
            c.activityBridge.activity = it
            it.setContent { LaWalletRoot(c) }
        }
    }

    protected fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitForText(text: String, timeoutMs: Long = 10_000, substring: Boolean = false) =
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeoutMs, condition)

    /** Settles animations, then records/compares `<name>_<lang>.png`. */
    protected fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/${name}_${Locale.getDefault().language}.png")
    }

    /** The node tagged [tag], looked up in the unmerged tree (clickable rows merge their children's tags away). */
    protected fun tag(tag: String): SemanticsNodeInteraction = rule.onNode(hasTestTag(tag), useUnmergedTree = true)

    /** Waits until the node tagged [tag] shows exactly [text]. */
    protected fun waitForTagText(tag: String, text: String, timeoutMs: Long = 10_000) = rule.waitUntil(timeoutMs) {
        rule.onAllNodes(hasTestTag(tag) and hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    companion object {
        /** A throwaway key, fixed so avatars and anything derived from it look the same in every run. */
        val TEST_SECRET = "7e".repeat(32)
    }
}

package io.lawallet.wallet.e2e

import android.Manifest
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.lawallet.wallet.LaWalletApp
import io.lawallet.wallet.MainActivity
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.testing.FakeInstance
import io.lawallet.wallet.testing.TestContainer
import io.lawallet.wallet.testing.loginThrowaway
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real MainActivity on a device, against a fresh [FakeInstance] per test: the app's container is swapped for a
 * [TestContainer] before launch and put back after. Runs in the side-by-side `.e2e` copy (scripts/test-device.sh), so
 * wiping its prefs between tests never touches a real install.
 *
 * LaWalletApp's own collectors (foreground/background, notification posting) stay bound to the original container:
 * tests drive [AppContainer.wallet]'s `active` themselves and observe payment notices on the test container.
 */
@RunWith(AndroidJUnit4::class)
abstract class E2eTest {
    // A fresh install asks for notifications on first launch; that dialog would cover the activity.
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    protected val app: LaWalletApp = ApplicationProvider.getApplicationContext()
    protected lateinit var instance: FakeInstance
    protected lateinit var c: AppContainer
    private lateinit var original: AppContainer
    protected var scenario: ActivityScenario<MainActivity>? = null

    /** The app's OkHttp; tests that fake an https host (a Lightning address) add an interceptor in front. */
    protected open fun http(): OkHttpClient = TestContainer.localOnlyHttp()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before fun swapInTestContainer() {
        original = app.container
        // Logged out, default settings: this test's account only (prefs are the .e2e copy's).
        app.getSharedPreferences("lawallet", 0).edit().clear().commit()
        instance = FakeInstance().start()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { c = TestContainer.create(app, instance, http = http()) }
        app.container = c
        // No image loading (avatars, community and provider logos): nothing leaves the device.
        coil3.SingletonImageLoader.setUnsafe(
            coil3.ImageLoader.Builder(app).components {
                add(coil3.intercept.Interceptor { chain -> coil3.request.ErrorResult(null, chain.request, java.io.IOException("no images in tests")) })
            }.build(),
        )
    }

    @After fun restoreContainer() {
        scenario?.close()
        c.scope.cancel()
        app.container = original
        app.getSharedPreferences("lawallet", 0).edit().clear().commit()
        instance.close()
    }

    /** Shows MainActivity (with [intent], as a launcher, link or NFC tap would); [loggedIn] first logs in with a throwaway key. */
    protected fun launch(loggedIn: Boolean = true, intent: Intent? = null) {
        if (loggedIn) runBlocking { c.loginThrowaway() }
        // In the foreground, as LaWalletApp's process observer sets it for its own container: balance polling runs.
        c.wallet.active.value = true
        scenario = ActivityScenario.launch(intent ?: Intent(app, MainActivity::class.java))
    }

    /** Back, as the system back gesture does. */
    protected fun back() = scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    protected fun string(id: Int, vararg args: Any): String = app.getString(id, *args)
    protected fun sats(n: Long): String = Currencies.formatSats(n)

    protected fun waitUntil(timeoutMs: Long = TIMEOUT, condition: () -> Boolean) = rule.waitUntil(timeoutMs, condition)
    protected fun has(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    protected fun shows(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    protected fun waitForTag(tag: String, timeoutMs: Long = TIMEOUT) = waitUntil(timeoutMs) { has(tag) }
    protected fun waitForText(text: String, timeoutMs: Long = TIMEOUT) = waitUntil(timeoutMs) { shows(text) }
    protected fun waitForTagText(tag: String, text: String, timeoutMs: Long = TIMEOUT) = waitUntil(timeoutMs) {
        rule.onAllNodes(hasTestTag(tag) and hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** The node tagged [tag], in the unmerged tree (clickable rows merge their children's tags away). */
    protected fun tag(tag: String): SemanticsNodeInteraction = rule.onNode(hasTestTag(tag), useUnmergedTree = true)
    /** The button labelled [text]. */
    protected fun button(text: String): SemanticsNodeInteraction = rule.onNode(hasText(text) and hasClickAction())

    /** Whether the activity's view tree holds a [type] (an AndroidView inside Compose, e.g. the camera's PreviewView). */
    protected fun hasView(type: Class<out View>): Boolean {
        fun walk(v: View): Boolean = type.isInstance(v) || (v is ViewGroup && (0 until v.childCount).any { walk(v.getChildAt(it)) })
        var found = false
        scenario!!.onActivity { found = walk(it.window.decorView) }
        return found
    }

    protected fun txRows() = rule.onAllNodes(hasTestTag("tx-row"), useUnmergedTree = true).fetchSemanticsNodes().size

    companion object {
        /** On an emulator, debug build: generous, every wait is on an explicit condition anyway. */
        const val TIMEOUT = 20_000L
    }
}

/**
 * LNURL-pay (`/.well-known/lnurlp/<name>`, a fixed [payMsat]) and LNURL-withdraw (`/withdraw`, a fixed [withdrawMsat])
 * endpoints on a local MockWebServer. [redirect] maps an https Lightning-address host onto it.
 */
class LnurlServer : Closeable {
    val server = MockWebServer()
    @Volatile var payMsat = 1_000_000L
    @Volatile var withdrawMsat = 2_000_000L
    /** Invoices handed to the withdraw callback. */
    val withdrawn = CopyOnWriteArrayList<String>()

    val base: String get() = server.url("/").toString().trimEnd('/')
    fun payUrl(name: String = "alice") = "$base/.well-known/lnurlp/$name"
    val withdrawUrl: String get() = "$base/withdraw"

    fun start(): LnurlServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.encodedPath.startsWith("/.well-known/lnurlp/") ->
                        json("""{"tag":"payRequest","callback":"$base/pay-cb","minSendable":$payMsat,"maxSendable":$payMsat,"metadata":"[[\"text/plain\",\"Test payee\"]]"}""")
                    url.encodedPath == "/pay-cb" -> json("""{"pr":"${FakeInstance.invoice(url.queryParameter("amount")!!.toLong() / 1000, "Test payee")}","routes":[]}""")
                    url.encodedPath == "/withdraw" ->
                        json("""{"tag":"withdrawRequest","callback":"$base/withdraw-cb","k1":"test-k1","minWithdrawable":$withdrawMsat,"maxWithdrawable":$withdrawMsat,"defaultDescription":"Test voucher"}""")
                    url.encodedPath == "/withdraw-cb" -> { withdrawn += url.queryParameter("pr")!!; json("""{"status":"OK"}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return this
    }

    override fun close() { runCatching { server.shutdown() } }

    /** `https://<host>/…` → this server, over http. Goes in front of TestContainer's localhost-only check. */
    fun redirect(host: String) = Interceptor { chain ->
        val u = chain.request().url
        if (u.host != host) chain.proceed(chain.request())
        else chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host(server.hostName).port(server.port).build()).build())
    }

    companion object {
        /** A bech32 `lnurl1…` for [url]. */
        fun lnurl(url: String) = Bech32.encodeBytes("lnurl", url.toByteArray())
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}

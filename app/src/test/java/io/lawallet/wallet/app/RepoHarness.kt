package io.lawallet.wallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.LaWalletApi
import io.lawallet.wallet.core.auth.AmberSigner
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A LaWallet instance on MockWebServer: answers "METHOD /path" (query ignored) from [routes], 404 otherwise. */
internal class StubInstance {
    val server = MockWebServer()
    val routes = ConcurrentHashMap<String, () -> MockResponse>()
    /** "METHOD /path?query" of every request, in order. */
    val hits = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                hits += "${request.method} ${url.encodedPath}${url.encodedQuery?.let { "?$it" } ?: ""}"
                bodies += request.body.readUtf8()
                return routes["${request.method} ${url.encodedPath}"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        on("GET /api/settings", "{}")
        on("POST /api/jwt", """{"token":"${jwt()}"}""")
        on("GET /api/wallet/addresses", "[]")
    }

    fun on(route: String, body: String, code: Int = 200) { routes[route] = { MockResponse().setResponseCode(code).setBody(body) } }
    val url: String get() = server.url("/").toString().trimEnd('/')

    companion object {
        fun jwt() = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${System.currentTimeMillis() / 1000 + 86_400}}""".toByteArray()) + ".s"
    }
}

/** Session + WalletRepository against a [StubInstance], as AppContainer wires them (Robolectric for Prefs). */
internal class RepoHarness {
    val instance = StubInstance()
    val context: Context = ApplicationProvider.getApplicationContext()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = OkHttpClient()
    val prefs = Prefs(context).apply { instanceUrl = instance.url }
    val session: Session

    init {
        io.lawallet.wallet.testing.FakeAndroidKeyStore.install()
        session = Session(prefs, http, scope, AmberSigner.Factory(context, ActivityBridge()), Avatars(context))
    }

    val wallet by lazy { WalletRepository(session, prefs, http, scope) }

    /** Logs in with a throwaway key; `/api/users/me` answers [me]. */
    suspend fun login(me: CurrentUser) {
        instance.on("GET /api/users/me", LaWalletApi.json.encodeToString(CurrentUser.serializer(), me))
        session.loginWithSecret(Keys.generate().secretHex, "nsec")
    }

    fun close() {
        scope.cancel()
        runCatching { instance.server.shutdown() }
    }
}

/** A throwaway NWC connection string; the relay is a closed local port, so nothing ever answers. */
internal fun nwcString(wallet: Keys = Keys.generate()) =
    "nostr+walletconnect://${wallet.pubkey}?relay=ws://127.0.0.1:9&secret=${Keys.generate().secretHex}"

/** Polls [check] for up to [ms] of real time (repositories work on real dispatchers). */
internal fun eventually(ms: Long = 5_000, message: String = "condition not met", check: () -> Boolean) {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(20) }
    assertTrue(message, check())
}

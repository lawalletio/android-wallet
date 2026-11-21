package io.lawallet.wallet.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.ExternalResource
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64

/**
 * A LaWallet instance stub on the device, so journeys can log in with a throwaway key and walk the logged-in
 * screens without any account on a real instance. The .bench build points here (app/build.gradle.kts) and may talk
 * cleartext to 127.0.0.1 only (app/src/bench/res/xml/network_security_config.xml).
 *
 * The account's wallet is a dummy NWC connection whose relay is this server too: it never answers, so the app
 * shows a wallet that is loading, and nothing reaches a real instance or wallet (rates and public Nostr profiles
 * are still fetched, as on any start).
 */
class FakeInstance : ExternalResource() {
    private val server = MockWebServer()

    override fun before() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = respond(request.requestUrl?.encodedPath.orEmpty())
        }
        server.start(InetAddress.getByName("127.0.0.1"), PORT)
    }

    override fun after() {
        server.shutdown()
        // Its accounts go with it: the next test starts logged out even when this one failed mid-journey.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("pm clear $targetPackage")
    }

    private fun respond(path: String): MockResponse = when {
        path == "/api/jwt" -> ok("""{"token":"$JWT"}""")
        path == "/api/users/me" -> ok("""{"userId":"bench","lightningAddress":"bench@127.0.0.1","nwcString":"$NWC","primaryUsername":"bench","primaryAddressMode":"CUSTOM_NWC"}""")
        path == "/api/wallet/addresses" -> ok("""[{"username":"bench","mode":"CUSTOM_NWC","remoteWalletId":"bench","isPrimary":true}]""")
        path == "/api/remote-wallets" -> ok("[$WALLET]")
        path == "/api/remote-wallets/bench" -> ok(WALLET)
        path.endsWith("/connection-string") -> ok("""{"connectionString":"$NWC"}""")
        path.endsWith("/balance") -> ok("""{"balanceSats":21000}""")
        path == "/api/nostr/profiles" -> ok("""{"profiles":[]}""")
        path == "/api/settings" || path == "/api/version" -> ok("{}")
        else -> MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"message":"not found"}}""")
    }

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        /** Keep in sync with the .bench DEFAULT_INSTANCE in app/build.gradle.kts. */
        const val PORT = 38080

        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        private fun randomHex() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** The app only reads `exp` (2100-01-01); nothing checks the signature. */
        private val JWT = "${b64("""{"alg":"none"}""")}.${b64("""{"exp":4102444800}""")}.x"
        private val NWC = "nostr+walletconnect://${randomHex()}?relay=ws://127.0.0.1:$PORT&secret=${randomHex()}"
        private const val WALLET = """{"id":"bench","name":"Bench","type":"NWC","status":"ACTIVE","isDefault":true}"""

        /** A throwaway Nostr secret key (hex, which the login screen accepts): never a real account's. */
        fun throwawaySecret() = randomHex()
    }
}

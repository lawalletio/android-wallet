package io.lawallet.wallet.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.testing.FakeInstance
import io.lawallet.wallet.testing.TestContainer
import io.lawallet.wallet.testing.loginThrowaway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var instance: FakeInstance
    private val scopes = mutableListOf<CoroutineScope>()
    private val instances = mutableListOf<FakeInstance>()

    private fun container(i: FakeInstance = instance) = TestContainer.create(context, i, CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })
    private fun newInstance() = FakeInstance().start().also { instances += it }

    @Before fun setUp() { instance = newInstance() }
    @After fun tearDown() { scopes.forEach { it.cancel() }; instances.forEach { it.close() } }

    @Test fun loginWithNsecMintsAJwtAndLoadsTheAccount() = runBlocking<Unit> {
        val c = container()
        val keys = c.loginThrowaway()
        assertEquals(AuthState.LoggedIn, c.session.auth.value)
        assertNotNull(c.prefs.jwt)
        assertEquals("nsec", c.prefs.loginMethod)
        assertEquals("satoshi@${instance.server.hostName}", c.session.me.value?.lightningAddress)
        assertEquals(keys.secretHex, c.session.secretHex())
        // The NIP-98 header is signed by this key, and the key is stored wrapped, never in the clear.
        assertTrue(instance.requests.single { it.path == "/api/jwt" }.auth!!.startsWith("Nostr "))
        assertFalse(c.prefs.signerSecretBlob!!.contains(keys.secretHex))
    }

    @Test fun aRestartRestoresTheSessionFromTheWrappedKey() = runBlocking<Unit> {
        val keys = container().loginThrowaway()
        val restarted = container()
        withTimeout(5_000) { restarted.session.auth.first { it == AuthState.LoggedIn } }
        withTimeout(5_000) { restarted.session.signer.first { it != null } }
        assertEquals(keys.pubkey, restarted.session.signer.value?.pubkey)
    }

    @Test fun logoutClearsTheKeyAndTheSession() = runBlocking<Unit> {
        val c = container()
        c.loginThrowaway()
        c.session.logout()
        assertEquals(AuthState.LoggedOut, c.session.auth.value)
        assertNull(c.session.secretHex())
        assertNull(c.session.me.value)
        assertNull(c.prefs.signerSecretBlob)
        assertNull(c.prefs.jwt)
        assertNull(c.prefs.loginMethod)
        assertNull(c.prefs.secretCached("me"))
        // The instance stays.
        assertEquals(instance.url, c.prefs.instanceUrl)
    }

    @Test fun switchInstanceKeepsTheKeyAndLogsInThere() = runBlocking<Unit> {
        val c = container()
        val keys = c.loginThrowaway()
        val other = newInstance().apply { username = "other" }
        assertEquals(other.url, c.session.switchInstance(other.url + "/"))
        assertEquals(other.url, c.prefs.instanceUrl)
        assertEquals(AuthState.LoggedIn, c.session.auth.value)
        assertEquals(keys.secretHex, c.session.secretHex())
        assertTrue(other.requests.any { it.path == "/api/jwt" })
        assertEquals("other@${other.server.hostName}", c.session.me.value?.lightningAddress)
    }

    @Test fun switchInstanceRejectsABadUrlAndKeepsTheCurrentOne() = runBlocking<Unit> {
        val c = container()
        c.loginThrowaway()
        try { c.session.switchInstance("  "); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(instance.url, c.prefs.instanceUrl)
        assertEquals(AuthState.LoggedIn, c.session.auth.value)
    }

    @Test fun normalizeUrl() {
        assertEquals("https://lawallet.example", Session.normalizeUrl(" lawallet.example/ "))
        assertEquals("http://localhost:8080", Session.normalizeUrl("http://localhost:8080/"))
        assertEquals("https://lawallet.example:8443", Session.normalizeUrl("https://lawallet.example:8443"))
        assertEquals("https://lawallet.example", Session.normalizeUrl("https://lawallet.example:443"))
        assertNull(Session.normalizeUrl(""))
    }

    // regression: 7711079 fix(offline): leave offline mode as soon as the app is opened
    @Test fun regression_7711079_retryIfOfflineLeavesOfflineModeAsSoonAsTheInstanceAnswers() = runBlocking<Unit> {
        val c = container()
        c.loginThrowaway()
        instance.offline = true
        c.session.refreshSettings()
        assertTrue(c.session.offline.value)

        instance.offline = false
        c.session.retryIfOffline()
        // Well before the 15 s background poll.
        withTimeout(3_000) { c.session.offline.first { !it } }
    }

    // regression: 7711079 fix(offline): leave offline mode as soon as the app is opened
    @Test fun regression_7711079_probingAnUnreachableInstanceDoesNotTurnOfflineModeOn() = runBlocking<Unit> {
        val c = container()
        c.loginThrowaway()
        val dead = newInstance().apply { offline = true }
        try { c.session.switchInstance(dead.url); fail() } catch (_: java.io.IOException) {}
        assertFalse(c.session.offline.value)
        assertEquals(instance.url, c.prefs.instanceUrl)
    }

    // regression: 143eee5 fix(profile): passkey logins show the account's primary profile
    @Test fun regression_143eee5_aSecondaryKeyShowsTheAccountsPrimaryProfile() = runBlocking<Unit> {
        val primary = Keys.generate().pubkey
        instance.accountPubkey = primary
        val c = container()
        val passkey = c.loginThrowaway()
        assertEquals(passkey.pubkey, c.session.signer.value?.pubkey)
        withTimeout(3_000) { c.session.accountPubkey.first { it == primary } }
    }

    // regression: 143eee5 fix(profile): passkey logins show the account's primary profile
    @Test fun regression_143eee5_aPrimaryKeyShowsItsOwnProfile() = runBlocking<Unit> {
        val c = container()
        val keys = c.loginThrowaway()
        withTimeout(3_000) { c.session.accountPubkey.first { it == keys.pubkey } }
    }
}

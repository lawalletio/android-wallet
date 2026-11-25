package io.lawallet.wallet.core.store

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sp get() = context.getSharedPreferences("lawallet", Context.MODE_PRIVATE)
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        sp.edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test fun setSeenKeepsTheNewest200() {
        prefs.setSeen("k", (1..250).map { "p$it" })
        assertEquals((51..250).map { "p$it" }, prefs.seen("k"))
        assertEquals(emptyList<String>(), prefs.seen("other"))
    }

    @Test fun cachedBalanceIsNullUntilSetAndZeroIsABalance() {
        assertNull(prefs.cachedBalance("k"))
        prefs.setCachedBalance("k", 0)
        assertEquals(0L, prefs.cachedBalance("k"))
        prefs.setCachedBalance("k", 21_000)
        assertEquals(21_000L, Prefs(context).cachedBalance("k"))
    }

    @Test fun notePrimaryKeepsAMostRecentFirstHistoryWithoutDuplicates() {
        prefs.notePrimary("a"); prefs.notePrimary("b"); prefs.notePrimary("b"); prefs.notePrimary("a")
        assertEquals(listOf("a", "b"), prefs.primaryHistory)
        (1..30).forEach { prefs.notePrimary("w$it") }
        assertEquals(20, prefs.primaryHistory.size)
        assertEquals("w30", prefs.primaryHistory.first())
    }

    @Test fun pendingActivationRoundTripsAndClears() {
        assertNull(prefs.pendingActivation)
        prefs.pendingActivation = "tok" to 1_700_000_000_000
        assertEquals("tok" to 1_700_000_000_000, Prefs(context).pendingActivation)
        prefs.pendingActivation = null
        assertNull(prefs.pendingActivation)
    }

    @Test fun clearSessionKeepsOnlyTheInstanceDisplayAndDiagnostics() {
        fillEverything()
        prefs.clearSession()
        assertEquals(
            setOf("instance", "payment-sound", "currencies", "bg-listener", "slide-to-pay", "lncurl-provider", "crash-prompt-declined", "last-exit-ts", "exit-log", "last-anr-trace"),
            sp.all.keys,
        )
        // The in-memory flows go with the disk.
        assertEquals(LockPrefs(), prefs.lock.value)
        assertEquals(NfcAutoPay(), prefs.nfcAutoPay.value)
        assertEquals(emptyMap<String, PaymentDestination>(), prefs.paymentDestinations.value)
        assertNull(prefs.loginMethod)
        assertNull(prefs.jwt)
    }

    @Test fun clearInstanceCachesKeepsTheKeyAndDevicePrefsButNotTheSession() {
        fillEverything()
        prefs.clearInstanceCaches()
        assertEquals(
            setOf(
                "instance", "payment-sound", "currencies", "bg-listener", "slide-to-pay", "lncurl-provider", "login-method", "signer-secret",
                "ext-signer-pubkey", "ext-signer-pkg", "lock", "nfc-autopay", "settings:https://old.example", "crash-prompt-declined", "last-exit-ts", "exit-log", "last-anr-trace",
            ),
            sp.all.keys,
        )
        assertEquals(LockPrefs(onStartup = true), prefs.lock.value)
        assertEquals(emptyMap<String, PaymentDestination>(), prefs.paymentDestinations.value)
        assertNull(prefs.jwt)
        assertEquals("nsec", prefs.loginMethod)
    }

    /** One value under every kind of key: session, caches, per-wallet state, device prefs, diagnostics. */
    private fun fillEverything() {
        prefs.instanceUrl = "https://old.example"
        prefs.settingsCache = "{}"
        prefs.loginMethod = "nsec"
        prefs.signerSecretBlob = "blob"
        prefs.externalSigner = "pk" to "pkg"
        prefs.jwt = "jwt"
        prefs.setPaymentSound("coin")
        prefs.setCurrencies(CurrencyState(selected = "ARS"))
        prefs.setBackgroundListener(false)
        prefs.setSlideToPay(false)
        prefs.lncurlProvider = "https://lncurl.example"
        prefs.setHideBalance(true)
        prefs.setAddressLocked("satoshi", true)
        prefs.setLock(LockPrefs(onStartup = true))
        prefs.setNfcAutoPay(NfcAutoPay(enabled = true))
        prefs.crashPromptDeclined = true
        prefs.lastExitTs = 1
        prefs.exitLog = listOf("exit")
        prefs.lastAnrTrace = "trace"
        prefs.contacts = listOf(Contact("bob@example.com"))
        prefs.setCached("addresses", ListSerializer(String.serializer()), listOf("a"))
        prefs.rememberDestination("hash", PaymentDestination("bob@example.com", savedAt = 1))
        prefs.setCachedBalance("k", 1)
        prefs.setActivityCache("k", "[]")
        prefs.setLastSeen("k", 1)
        prefs.setSeen("k", listOf("p"))
        prefs.pendingActivation = "tok" to 1
        prefs.notePrimary("w1")
        prefs.bonusSats = 1
        prefs.selectedAddress = "alias"
        prefs.ratesJson = "{}"
    }
}

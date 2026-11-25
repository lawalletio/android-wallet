package io.lawallet.wallet.app

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import io.lawallet.wallet.core.store.LockPrefs
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())
    private val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).get()
    private var passes = true
    private val prompts = mutableListOf<String>()
    private fun lock(settings: LockPrefs): AppLock {
        prefs.setLock(settings)
        return AppLock(prefs, ActivityBridge()) { _, title -> prompts += title; passes }
    }

    @Test fun startsLockedOnlyWhenStartupOrAccessIsOn() {
        assertFalse(lock(LockPrefs(payments = true, sensitive = true)).locked.value)
        assertTrue(lock(LockPrefs(onStartup = true)).locked.value)
        assertTrue(lock(LockPrefs(onAccess = true)).locked.value)
    }

    @Test fun paymentsFromTheMinimumUpAsk() {
        val l = lock(LockPrefs(payments = true, paymentsMinSats = 1000))
        assertFalse(l.paymentNeedsAuth(999))
        assertTrue(l.paymentNeedsAuth(1000))
        assertTrue(l.paymentNeedsAuth(50_000))
        assertFalse(lock(LockPrefs(payments = false, paymentsMinSats = 0)).paymentNeedsAuth(1_000_000))
        assertTrue(lock(LockPrefs(payments = true)).paymentNeedsAuth(0)) // 0 = every payment
    }

    @Test fun unlockedPaymentAsksOnlyAboveTheThreshold() = runTest {
        val l = lock(LockPrefs(payments = true, paymentsMinSats = 1000))
        assertTrue(l.allowPayment(activity, "Pay", 10))
        assertEquals(emptyList<String>(), prompts)
        assertTrue(l.allowPayment(activity, "Pay", 1000))
        passes = false
        assertFalse(l.allowPayment(activity, "Pay", 1000))
        assertEquals(listOf("Pay", "Pay"), prompts)
    }

    @Test fun lockedPaymentAlwaysAsksAndPassingUnlocksTheApp() = runTest {
        val l = lock(LockPrefs(onAccess = true, payments = true, paymentsMinSats = 1_000_000))
        l.nfcPaying.value = true
        passes = false
        assertFalse(l.allowPayment(activity, "Pay", 1)) // under the threshold, but the app is locked
        assertTrue(l.locked.value)
        assertTrue(l.nfcPaying.value)
        passes = true
        assertTrue(l.allowPayment(activity, "Pay", 1))
        assertFalse(l.locked.value)
        assertFalse(l.nfcPaying.value)
        assertEquals(2, prompts.size)
    }

    @Test fun nfcTapHoldsTheLockPromptThenRoutesToThePayment() {
        val l = lock(LockPrefs(onAccess = true, payments = true))
        l.nfcTapped()
        assertTrue(l.nfcHold.value)
        l.nfcRouted(onPayment = true)
        assertFalse(l.nfcHold.value)
        assertTrue(l.nfcPaying.value) // the lock screen steps aside for the payment
        l.onRoute(payment = true)
        assertTrue(l.nfcPaying.value)
        l.onRoute(payment = false) // left without paying: the lock screen comes back
        assertFalse(l.nfcPaying.value)
    }

    @Test fun nfcTapNotLandingOnAPaymentReleasesTheHold() {
        val l = lock(LockPrefs(onAccess = true, payments = true))
        l.nfcTapped()
        l.nfcRouted(onPayment = false)
        assertFalse(l.nfcHold.value)
        assertFalse(l.nfcPaying.value)
    }

    @Test fun nfcTapWithoutPaymentLockNeverHolds() {
        val l = lock(LockPrefs(onAccess = true, payments = false))
        l.nfcTapped()
        assertFalse(l.nfcHold.value)
        l.nfcRouted(onPayment = true)
        assertFalse(l.nfcPaying.value) // no hold, so the lock screen stays up
    }
}

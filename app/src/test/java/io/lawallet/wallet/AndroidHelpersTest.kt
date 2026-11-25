package io.lawallet.wallet

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.BankOwner
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.store.Prefs
import io.lawallet.wallet.service.TapWakeService
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.resumeRoute
import io.lawallet.wallet.ui.screens.startCashOut
import io.lawallet.wallet.ui.screens.startWithdraw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Small helpers that need android.net.Uri, resources or an AppContainer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidHelpersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun lanHostsArePrivateRangesAndDotLocal() {
        listOf("http://192.168.1.5:3000", "http://10.0.2.2:3052", "http://172.16.0.1", "http://172.31.255.1", "http://127.0.0.1:8080", "http://wallet.local").forEach {
            assertTrue(it, isLanHost(it))
        }
        listOf("https://lawallet.io", "http://172.32.0.1", "http://11.0.0.1", "https://local.example.com", "not a url").forEach {
            assertFalse(it, isLanHost(it))
        }
    }

    @Test fun routeBuildersEncodeWhatTheyCarry() {
        assertEquals("settings/plugins/add?url=https%3A%2F%2Fgithub.com%2Fo%2Fr%2Ftree%2Fmain%2Fplugins%2Fx%3Fa%3D1%26b", R0.pluginAdd("https://github.com/o/r/tree/main/plugins/x?a=1&b"))
        assertEquals("auth/email?token=tok_1&host=10.0.2.2%3A3052", R0.emailAuth("tok_1", "10.0.2.2:3052"))
        assertEquals("activate/t1", R0.activate("t1"))
        assertEquals("sweep/a/b", R0.sweep("a", "b"))
        assertEquals("settings/remote-wallets-add?step=lncurl", R0.addRemoteWallet("lncurl"))
    }

    @Test fun afterAuthResumesAnActivationForAnHour() {
        val now = 10_000_000L
        assertEquals("activate/t1", resumeRoute("t1" to now - 3_599_999, now))
        assertNull(resumeRoute("t1" to now - 3_600_000, now))
        assertNull(resumeRoute(null, now))
    }

    private fun container(): Pair<AppContainer, CoroutineScope> {
        Prefs(app).instanceUrl = "http://127.0.0.1:9" // nothing listens: no real instance is reached
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return AppContainer(app, scope, OkHttpClient()) to scope
    }

    @Test fun startCashOutFallsBackToTheLookedUpHolder() {
        val (c, scope) = container()
        try {
            val account = BankAccount("0000003100010000000001", owners = listOf(BankOwner("F", "27123456784", "Ana Pérez"), BankOwner(name = "Co-owner")))
            assertEquals(R0.CASHOUT_AMOUNT, startCashOut(c, account, "Ana"))
            assertEquals("Ana", c.flow.cashOut.holderName)
            startCashOut(c, account, null)
            assertEquals("Ana Pérez", c.flow.cashOut.holderName)
            assertEquals(account, c.flow.cashOut.account)
            startCashOut(c, BankAccount("ana.mp"), null)
            assertEquals("", c.flow.cashOut.holderName) // unresolved: typed on the next step
        } finally { scope.cancel() }
    }

    @Test fun startWithdrawSendsFromTheOtherWalletIntoTheOneInUse() {
        val (c, scope) = container()
        try {
            val from = RemoteWallet("w-from", "Old")
            val to = RemoteWallet("w-to", "Main", isDefault = true)
            assertEquals(R0.SEND_AMOUNT, startWithdraw(c, from, to))
            val s = c.flow.send
            assertEquals(Destination.Wallet("w-to", "Main"), s.destination)
            assertEquals("w-from" to "Old", s.source to s.sourceLabel)
            assertEquals("Main", s.recipientLabel)
            assertTrue(s.toActive)
        } finally { scope.cancel() }
    }

    // regression: 7debed2 fix(nfc): open the app when a POS reads us instead of us reading it
    @Test fun regression_7debed2_posReadOpensTheWallet() {
        val service = Robolectric.buildService(TapWakeService::class.java).create().get()
        val selectNdef = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00)
        assertEquals(listOf(0x6A.toByte(), 0x82.toByte()), service.processCommandApdu(selectNdef, null).toList()) // not a tag: drop the link
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    // regression: 3582739 fix(nfc): also answer the NDEF v1 AID so a POS tap opens the wallet
    @Test fun regression_3582739_bothNdefAidsAreClaimed() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, TapWakeService::class.java), PackageManager.GET_META_DATA)
        assertEquals(R.xml.tap_wake_aid, info.metaData.getInt("android.nfc.cardemulation.host_apdu_service"))
        val aids = mutableListOf<String>()
        app.resources.getXml(R.xml.tap_wake_aid).use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "aid-filter") aids += p.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
        }
        assertEquals(setOf("D2760000850101", "D2760000850100"), aids.toSet())
    }
}

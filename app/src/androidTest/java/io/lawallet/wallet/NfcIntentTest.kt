package io.lawallet.wallet

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Parcelable
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.e2e.E2eTest
import io.lawallet.wallet.e2e.LnurlServer
import io.lawallet.wallet.testing.FakeInstance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MainActivity.handle: an NFC tag (NDEF) or a `lightning:` link opens the send flow on the payment it carries. */
class NfcIntentTest : E2eTest() {
    private val lnurl = LnurlServer().start()

    @After fun stopLnurl() = lnurl.close()

    private fun ndef(url: String) = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).setClass(app, MainActivity::class.java)
        .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf<Parcelable>(NdefMessage(NdefRecord.createUri(url))))

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(app, MainActivity::class.java)

    /** The pay screen is up (confirm button, slide-to-pay off) for what was tapped. */
    private fun awaitPayScreen() {
        waitForTag("pay-confirm")
        waitUntil { c.flow.send.amountSats != null }
    }

    @Test fun anNdefTagWithAnInvoiceOpensItsPayment() {
        val invoice = FakeInstance.invoice(1_500, "Tapped")
        c.prefs.setSlideToPay(false)
        launch(intent = ndef("lightning:$invoice"))
        awaitPayScreen()
        assertEquals(invoice, c.flow.send.bolt11)
        assertEquals(1_500L, c.flow.send.amountSats)
        assertTrue(c.flow.send.viaNfc)
    }

    @Test fun aLightningLinkWhileTheAppIsOpenOpensItsPayment() {
        val invoice = FakeInstance.invoice(2_000, "Linked")
        c.prefs.setSlideToPay(false)
        launch()
        waitForTag("home-root")
        // singleTask: the running activity gets it through onNewIntent.
        app.startActivity(view("lightning:$invoice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitPayScreen()
        assertEquals(invoice, c.flow.send.bolt11)
        assertEquals(false, c.flow.send.viaNfc)
    }

    @Test fun anNdefTagWithAnLnurlOpensItsPayment() {
        c.prefs.setSlideToPay(false)
        launch(intent = ndef("lightning:${LnurlServer.lnurl(lnurl.payUrl())}"))
        awaitPayScreen()
        assertEquals(lnurl.payUrl(), (c.flow.send.destination as Destination.LnurlPay).lnurlpUrl)
        assertEquals(1_000L, c.flow.send.amountSats)
    }
}

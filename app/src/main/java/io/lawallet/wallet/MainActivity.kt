package io.lawallet.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.lawallet.wallet.app.NfcBus
import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.ln.NdefRecordData
import io.lawallet.wallet.core.ln.NdefUrl
import io.lawallet.wallet.ui.Intents
import io.lawallet.wallet.ui.LaWalletRoot
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val container get() = (application as LaWalletApp).container
    private var pendingResult: ((Intent?) -> Unit)? = null
    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingResult?.invoke(it.data.takeIf { _ -> it.resultCode == RESULT_OK }); pendingResult = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash hands off at once to LoadingScreen (same icon + progress bar + version).
        installSplashScreen()
        // Dark-only UI: light status/nav bar icons regardless of the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        container.activityBridge.activity = this
        container.activityBridge.launch = { intent, cb -> pendingResult = cb; resultLauncher.launch(intent) }
        setContent { LaWalletRoot(container) }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (container.activityBridge.activity === this) { container.activityBridge.activity = null; container.activityBridge.launch = null }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Reader mode keeps taps inside the app (no chooser) and gives us the tag directly.
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, ::onTag,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B, null,
        )
    }

    override fun onPause() {
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    // Each silent drop gets a Trail line (never the URL: a card link is a secret).
    private fun onTag(tag: Tag) {
        val ndef = Ndef.get(tag) ?: return Trail.add("nfc", "tag without NDEF techs=${tag.techList.joinToString { it.substringAfterLast('.') }}", error = true)
        val msg = ndef.cachedNdefMessage ?: runCatching { ndef.connect(); ndef.ndefMessage.also { ndef.close() } }
            .onFailure { Trail.add("nfc", "NDEF read failed ${it.javaClass.simpleName}", error = true) }.getOrNull() ?: return
        dispatchNdef(msg)
    }

    private var lastTap: Pair<String, Long>? = null

    private fun dispatchNdef(msg: NdefMessage) {
        val url = NdefUrl.firstPaymentUrl(msg.records.map { NdefRecordData(it.tnf, it.type, it.payload) })
            ?: return Trail.add("nfc", "no payment link in ${msg.records.size} record(s) tnf=${msg.records.joinToString { "${it.tnf}/${String(it.type)}" }}", error = true)
        runOnUiThread {
            // A POS phone (HCE) stays in the field and re-presents its tag: one tap, one payment screen.
            val now = System.currentTimeMillis()
            if (lastTap?.let { it.first == url && now - it.second < 4_000 } == true) return@runOnUiThread Trail.add("nfc", "duplicate tap ignored")
            lastTap = url to now
            Trail.add("nfc", if (NdefUrl.isInvoice(url)) "invoice tapped" else "card link tapped")
            // Invoices are for us to pay; card links charge the invoice the receive screen shows.
            if (NdefUrl.isInvoice(url)) Intents.payloads.trySend(Intents.NFC + url)
            else NfcBus.consumer?.invoke(url) ?: Intents.payloads.trySend(url)
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("route")?.let { Intents.routes.trySend(it); return }
        // Emulators have no NFC: `--es devNfc lightning:lnbc…` runs a tap through the same path.
        if (BuildConfig.DEBUG) intent.getStringExtra("devNfc")?.let { Intents.payloads.trySend(Intents.NFC + it); return }
        if (BuildConfig.DEBUG) intent.getStringExtra("devPayment")?.let { type ->
            container.scope.launch { container.wallet.simulatePayment(type != "outgoing", intent.getLongExtra("devSats", 2_100)) }
            return
        }
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val msgs = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()?.toTypedArray()
                msgs?.firstOrNull()?.let(::dispatchNdef)
            }
            Intent.ACTION_VIEW -> intent.dataString?.let { Intents.payloads.trySend(it) }
        }
    }
}

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
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.screens.findOwnCard
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val container get() = (application as LaWalletApp).container
    private var pendingResult: ((Intent?) -> Unit)? = null
    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingResult?.invoke(it.data.takeIf { _ -> it.resultCode == RESULT_OK }); pendingResult = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash hands off at once to LoadingScreen (same icon + progress bar + version).
        installSplashScreen()
        // Dark-only UI: light status/nav bar icons regardless of the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        container.activityBridge.activity = this
        container.activityBridge.launch = { intent, cb -> pendingResult = cb; resultLauncher.launch(intent) }
        setContent { LaWalletRoot(container) }
        io.lawallet.wallet.core.diag.Perf.attach(window, (if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay)?.refreshRate ?: 60f)
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // Debug builds pointed at a dev server on the LAN (only they declare it): Android 17 blocks it otherwise.
            if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 37 && isLanHost(container.prefs.instanceUrl)) add(LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        io.lawallet.wallet.core.diag.Perf.detach(window)
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
        dispatchNdef(msg, tag)
    }

    private var lastTap: Pair<String, Long>? = null

    /** [tag]: the chip, when we have it; its UID tells one of the account's own cards from anyone else's. */
    private fun dispatchNdef(msg: NdefMessage, tag: Tag?) {
        val url = NdefUrl.firstPaymentUrl(msg.records.map { NdefRecordData(it.tnf, it.type, it.payload) })
            ?: return Trail.add("nfc", "no payment link in ${msg.records.size} record(s) tnf=${msg.records.joinToString { "${it.tnf}/${String(it.type)}" }}", error = true)
        runOnUiThread {
            // A POS phone (HCE) stays in the field and re-presents its tag: one tap, one payment screen.
            val now = System.currentTimeMillis()
            if (lastTap?.let { it.first == url && now - it.second < 4_000 } == true) return@runOnUiThread Trail.add("nfc", "duplicate tap ignored")
            lastTap = url to now
            Trail.add("nfc", if (NdefUrl.isInvoice(url)) "invoice tapped" else "card link tapped")
            // Invoices are for us to pay; card links charge the invoice the receive screen shows.
            if (NdefUrl.isInvoice(url)) { container.lock.nfcTapped(); Intents.payloads.trySend(Intents.NFC + url) }
            else NfcBus.consumer?.invoke(url) ?: onCardLink(url, tag?.id?.joinToString("") { "%02x".format(it) })
        }
    }

    /** A card link with no invoice waiting for it: our own card opens My Cards on it, anyone else's goes to Withdraw. */
    private fun onCardLink(url: String, uid: String?) = container.scope.launch {
        val mine = uid?.let { findOwnCard(container, it) }
        if (mine != null) {
            Trail.add("nfc", "own card tapped")
            container.flow.focusCard.value = mine.id
            Intents.routes.trySend(R0.CARDS)
        } else {
            container.lock.nfcTapped()
            Intents.payloads.trySend(url)
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("route")?.let { Intents.routes.trySend(it); return }
        // Emulators have no NFC: `--es devNfc lightning:lnbc…` runs a tap through the same path; add
        // `--es devCardUid <chip uid hex>` to make it a card tap (one of ours opens My Cards on it).
        if (BuildConfig.DEBUG) intent.getStringExtra("devCardUid")?.let { onCardLink(intent.getStringExtra("devNfc").orEmpty(), it); return }
        if (BuildConfig.DEBUG) intent.getStringExtra("devNfc")?.let { Intents.payloads.trySend(Intents.NFC + it); return }
        if (BuildConfig.DEBUG) intent.getStringExtra("devPayment")?.let { type ->
            container.scope.launch { container.wallet.simulatePayment(type != "outgoing", intent.getLongExtra("devSats", 2_100)) }
            return
        }
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val msgs = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()?.toTypedArray()
                val tag = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                msgs?.firstOrNull()?.let { dispatchNdef(it, tag) }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let { Intents.payloads.trySend(it) }
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private val PRIVATE_IPV4 = Regex("""^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\.""")

/** A private-network server (192.168.x.x, 10.x, 172.16–31.x, *.local), as opposed to a public instance. */
internal fun isLanHost(url: String): Boolean {
    val host = android.net.Uri.parse(url).host ?: return false
    return PRIVATE_IPV4.containsMatchIn(host) || host.endsWith(".local")
}

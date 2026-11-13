package io.lawallet.wallet.app

import android.content.Context
import io.lawallet.wallet.core.auth.AmberSigner
import io.lawallet.wallet.core.auth.Passkeys
import io.lawallet.wallet.core.fx.YadioRates
import io.lawallet.wallet.core.ln.BoltcardPay
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.ScanRouter
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired object graph — one instance per process. */
class AppContainer(context: Context) {
    // Long-lived collectors live here: an exception escaping one is reported, not a process crash.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Diagnostics.nonFatal(e) })
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    val prefs = Prefs(context)
    val activityBridge = ActivityBridge()
    val avatars = Avatars(context.applicationContext)
    val session = Session(prefs, http, scope, AmberSigner.Factory(context.applicationContext, activityBridge), avatars) { url ->
        // A remote signer asks for approval in the browser; it may come from a background request.
        runCatching { context.applicationContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val wallet = WalletRepository(session, prefs, http, scope)
    val rates = RatesRepository(YadioRates(http), prefs, scope)
    val lnurl = Lnurl(http)
    val scanRouter = ScanRouter(lnurl)
    val boltcardPay = BoltcardPay(lnurl)
    val passkeys = Passkeys({ session.api.value }, { session.instanceHost.substringBefore(':') })
    val flow = FlowStore()
    val lock = AppLock(prefs, activityBridge)
    val onchain = OnchainStore(context.applicationContext)
    val bip39 by lazy { io.lawallet.wallet.core.btc.Bip39(context.resources.openRawResource(io.lawallet.wallet.R.raw.bip39_english).bufferedReader().readLines()) }
    val updater = Updater(context.applicationContext, http)
}

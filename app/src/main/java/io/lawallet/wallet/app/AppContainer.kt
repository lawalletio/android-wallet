package io.lawallet.wallet.app

import android.content.Context
import io.lawallet.wallet.core.auth.AmberSigner
import io.lawallet.wallet.core.auth.Passkeys
import io.lawallet.wallet.core.fx.YadioRates
import io.lawallet.wallet.core.ln.BoltcardPay
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.ScanRouter
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired object graph — one instance per process. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    val prefs = Prefs(context)
    val activityBridge = ActivityBridge()
    val session = Session(prefs, http, scope, AmberSigner.Factory(context.applicationContext, activityBridge))
    val wallet = WalletRepository(session, prefs, http, scope)
    val rates = RatesRepository(YadioRates(http), prefs, scope)
    val lnurl = Lnurl(http)
    val scanRouter = ScanRouter(lnurl)
    val boltcardPay = BoltcardPay(lnurl)
    val passkeys = Passkeys({ session.api.value }, { session.instanceHost.substringBefore(':') })
    val flow = FlowStore()
}

package io.lawallet.wallet

import android.app.Application
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okio.Path.Companion.toOkioPath
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.app.Diagnostics
import io.lawallet.wallet.service.Notifications
import io.lawallet.wallet.service.PaymentListenerService
import io.lawallet.wallet.service.PaymentSound
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LaWalletApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** One image pipeline: the app's OkHttp pool, memory + disk caches, SVG for community logos. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            add(SvgDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(32L * 1024 * 1024).build() }
        .crossfade(180)
        .build()

    val inForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Logged in, listener enabled and a wallet connected. */
    private fun shouldListen() = container.let { it.session.auth.value == AuthState.LoggedIn && it.prefs.backgroundListener.value && it.wallet.client.value != null }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build())
        }
        container = AppContainer(this)
        Notifications.createChannels(this)
        val c = container
        Diagnostics.start(this, c)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                c.wallet.active.value = true
                c.rates.active.value = true
                runCatching { Notifications.clearPayments(this@LaWalletApp) }
                c.scope.launch { c.updater.checkIfDue() }
                // The listener may have stopped while the process lived on (task-manager stop, a
                // denied restart); the combine below only reacts to changes, so re-assert it here.
                if (shouldListen()) PaymentListenerService.start(this@LaWalletApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                c.rates.active.value = false
                // Balance polling stops in the background; the relay notification stream keeps
                // running (kept alive by the listener service when enabled).
                c.wallet.active.value = false
            }
        })

        c.scope.launch {
            c.wallet.notices.collect { notice ->
                // An escaped exception would end this collector — and every later payment notice.
                runCatching { PaymentSound.play(this@LaWalletApp, c.prefs.paymentSound.value, notice.id) }.onFailure { Diagnostics.nonFatal(it) }
                if (!inForeground) runCatching { Notifications.showPayment(this@LaWalletApp, notice) }.onFailure { Diagnostics.nonFatal(it) }
            }
        }

        c.scope.launch {
            combine(c.session.auth, c.prefs.backgroundListener, c.wallet.client) { _, _, _ -> shouldListen() }.collect { listen ->
                if (listen) { if (inForeground) PaymentListenerService.start(this@LaWalletApp) } else PaymentListenerService.stop(this@LaWalletApp)
            }
        }
    }
}

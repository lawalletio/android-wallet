package io.lawallet.wallet

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        val c = container

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                c.wallet.active.value = true
                c.rates.active.value = true
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
                PaymentSound.play(this@LaWalletApp, c.prefs.paymentSound.value, notice.id)
                if (!inForeground) Notifications.showPayment(this@LaWalletApp, notice)
            }
        }

        c.scope.launch {
            combine(c.session.auth, c.prefs.backgroundListener, c.wallet.client) { auth, bg, client ->
                auth == AuthState.LoggedIn && bg && client != null
            }.collect { listen ->
                if (listen) { if (inForeground) PaymentListenerService.start(this@LaWalletApp) } else PaymentListenerService.stop(this@LaWalletApp)
            }
        }
    }
}

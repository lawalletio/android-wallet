package io.lawallet.wallet

import android.app.Application
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

class LaWalletApp : Application() {
    lateinit var container: AppContainer
        private set

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
                // The foreground service keeps the wallet active in the background when enabled.
                c.wallet.active.value = c.prefs.backgroundListener.value && c.session.auth.value == AuthState.LoggedIn
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

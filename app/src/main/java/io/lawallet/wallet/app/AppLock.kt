package io.lawallet.wallet.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.lawallet.wallet.core.store.LockPrefs
import io.lawallet.wallet.core.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Settings → Locking: device-lock gates on startup or every access to the app, on payments from a minimum, and on showing secrets. */
class AppLock(private val prefs: Prefs, private val bridge: ActivityBridge) {
    /** True while the lock screen covers the app. */
    val locked = MutableStateFlow(prefs.lock.value.let { it.onStartup || it.onAccess })
    private var relock = false

    /** An NFC tap is opening a payment: the lock screen holds its prompt, the payment's own stands in for it (one prompt, not two). */
    val nfcHold = MutableStateFlow(false)
    /** That payment is on screen: the lock screen steps aside until it's paid or left. */
    val nfcPaying = MutableStateFlow(false)
    fun nfcTapped() { if (settings.payments) nfcHold.value = true }
    /** The tapped payload has been routed; [onPayment]: it landed on the send flow. */
    fun nfcRouted(onPayment: Boolean) { nfcPaying.value = onPayment && nfcHold.value; nfcHold.value = false }
    /** Leaving the payment without paying brings the lock screen back. */
    fun onRoute(payment: Boolean) { if (!payment) nfcPaying.value = false }

    private val settings: LockPrefs get() = prefs.lock.value
    val sensitive: Boolean get() = settings.sensitive
    private fun paymentNeedsAuth(sats: Long) = settings.let { it.payments && sats >= it.paymentsMinSats }

    /** While locked (an NFC payment standing in for the lock) every amount asks, and passing unlocks the app too. */
    suspend fun allowPayment(activity: FragmentActivity, title: String, sats: Long): Boolean {
        if (!locked.value) return !paymentNeedsAuth(sats) || authenticate(activity, title)
        return authenticate(activity, title).also { if (it) { locked.value = false; nfcPaying.value = false } }
    }

    /** Relocks every time the app comes back to the foreground, except from a signer round trip or a PIN screen (API 28–29) we started. */
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_STOP -> relock = settings.onAccess && bridge.awaiting == 0 && !prompting
            Lifecycle.Event.ON_START -> if (relock) { relock = false; locked.value = true }
            else -> {}
        }
    })

    companion object {
        private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        @Volatile private var prompting = false

        /** A fingerprint, face or screen lock is set up. */
        fun available(context: Context) = BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

        /** Asks for the device lock. True once it passes, or when the device has none to ask for (else a removed screen lock would lock the wallet away). */
        suspend fun authenticate(activity: FragmentActivity, title: String): Boolean {
            if (!available(activity)) return true
            prompting = true
            return try {
                suspendCancellableCoroutine { cont ->
                    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
                    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build())
                }
            } finally { prompting = false }
        }
    }
}

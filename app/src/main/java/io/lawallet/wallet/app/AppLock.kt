package io.lawallet.wallet.app

import android.content.Context
import android.os.SystemClock
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

/** Settings → Locking: device-lock gates on opening the app, on payments from a minimum, and on showing secrets. */
class AppLock(private val prefs: Prefs) {
    /** True while the lock screen covers the app. */
    val locked = MutableStateFlow(prefs.lock.value.onOpen)
    private var stoppedAt = 0L

    private val settings: LockPrefs get() = prefs.lock.value
    val sensitive: Boolean get() = settings.sensitive
    fun paymentNeedsAuth(sats: Long) = settings.let { it.payments && sats >= it.paymentsMinSats }

    /** Relocks after [GRACE_MS] in the background, so a passkey or signer round trip doesn't ask again. */
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
            Lifecycle.Event.ON_START -> if (settings.onOpen && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= GRACE_MS) locked.value = true
            else -> {}
        }
    })

    companion object {
        private const val GRACE_MS = 30_000L
        private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /** A fingerprint, face or screen lock is set up. */
        fun available(context: Context) = BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

        /** Asks for the device lock. True once it passes, or when the device has none to ask for (else a removed screen lock would lock the wallet away). */
        suspend fun authenticate(activity: FragmentActivity, title: String): Boolean {
            if (!available(activity)) return true
            return suspendCancellableCoroutine { cont ->
                BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
                }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build())
            }
        }
    }
}

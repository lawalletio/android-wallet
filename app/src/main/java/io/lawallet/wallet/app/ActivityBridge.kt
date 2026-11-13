package io.lawallet.wallet.app

import android.app.Activity
import android.content.Intent
import io.lawallet.wallet.core.auth.AmberSigner
import kotlinx.coroutines.CompletableDeferred

/** Lets non-UI code (the Amber signer) run an Activity-result intent on the current Activity. */
class ActivityBridge : AmberSigner.IntentLauncher {
    var launch: ((Intent, (Intent?) -> Unit) -> Unit)? = null
    var activity: Activity? = null
    /** An intent we launched (Amber) is in front: leaving for it isn't leaving the app. */
    @Volatile var awaiting = 0
        private set

    override suspend fun launch(intent: Intent): Intent? {
        val l = launch ?: return null
        val result = CompletableDeferred<Intent?>()
        awaiting++
        try {
            l(intent) { result.complete(it) }
            return result.await()
        } finally { awaiting-- }
    }
}

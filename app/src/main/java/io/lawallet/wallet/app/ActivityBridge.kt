package io.lawallet.wallet.app

import android.app.Activity
import android.content.Intent
import io.lawallet.wallet.core.auth.AmberSigner
import kotlinx.coroutines.CompletableDeferred

/** Lets non-UI code (the Amber signer) run an Activity-result intent on the current Activity. */
class ActivityBridge : AmberSigner.IntentLauncher {
    var launch: ((Intent, (Intent?) -> Unit) -> Unit)? = null
    var activity: Activity? = null

    override suspend fun launch(intent: Intent): Intent? {
        val l = launch ?: return null
        val result = CompletableDeferred<Intent?>()
        l(intent) { result.complete(it) }
        return result.await()
    }
}

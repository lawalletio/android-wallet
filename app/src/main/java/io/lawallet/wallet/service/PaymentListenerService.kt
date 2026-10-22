package io.lawallet.wallet.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.lawallet.wallet.LaWalletApp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Diagnostics

/**
 * Keeps the NWC relay subscription alive while the app is in the background so payments
 * arrive as notifications in real time (there is no server push — see the plan).
 */
class PaymentListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // startForeground() must run within a few seconds of startForegroundService(); do it in
    // onCreate, before touching the app graph, so a slow cold start can't trip the deadline.
    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Diagnostics.breadcrumb("fgs", "stopped")
        super.onDestroy()
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_listener_title))
            .setContentText(getString(R.string.notif_listener_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifications.openAppIntent(this))
            .build()
        try {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a START_STICKY restart after a low-memory kill. Crashing here
            // loops until the system gives up on the service; stop cleanly instead — the app
            // restarts the listener next time it comes to the foreground.
            if (Build.VERSION.SDK_INT < 31 || e !is ForegroundServiceStartNotAllowedException) throw e
            starting = false
            Diagnostics.event("fgs.start_denied")
            stopSelf()
            return
        }
        starting = false
        if (!running) Diagnostics.breadcrumb("fgs", "started")
        running = true
        if (stopRequested) { stopRequested = false; stopSelf() }
    }

    // Main-thread only: every caller (app collectors, BootReceiver, the service callbacks) runs there.
    companion object {
        /** In the foreground right now — read by [Diagnostics] for the process-state summary. */
        var running = false
            private set
        /** startForegroundService() sent, startForeground() not reached yet. */
        private var starting = false
        private var stopRequested = false

        fun start(context: Context) = runCatching {
            stopRequested = false
            ContextCompat.startForegroundService(context, Intent(context, PaymentListenerService::class.java))
            starting = true
        }

        /**
         * Stopping a service between startForegroundService() and its startForeground() crashes the
         * app (ForegroundServiceDidNotStartInTimeException), so a stop that lands in that window
         * is deferred until the service has gone foreground.
         */
        fun stop(context: Context) {
            if (starting) stopRequested = true else context.stopService(Intent(context, PaymentListenerService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val c = (context.applicationContext as LaWalletApp).container
        if (c.prefs.backgroundListener.value && c.prefs.loginMethod != null) PaymentListenerService.start(context)
    }
}

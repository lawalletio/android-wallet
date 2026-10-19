package io.lawallet.wallet.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.lawallet.wallet.LaWalletApp
import io.lawallet.wallet.R

/**
 * Keeps the NWC relay subscription alive while the app is in the background so payments
 * arrive as notifications in real time (there is no server push — see the plan).
 */
class PaymentListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_listener_title))
            .setContentText(getString(R.string.notif_listener_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifications.openAppIntent(this))
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        (application as LaWalletApp).container.wallet.active.value = true
        return START_STICKY
    }

    companion object {
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PaymentListenerService::class.java))
        }

        fun stop(context: Context) = context.stopService(Intent(context, PaymentListenerService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = (context.applicationContext as LaWalletApp).container
        if (c.prefs.backgroundListener.value && c.prefs.loginMethod != null) PaymentListenerService.start(context)
    }
}

package io.lawallet.wallet.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.lawallet.wallet.MainActivity
import io.lawallet.wallet.R
import io.lawallet.wallet.app.PaymentNotice
import io.lawallet.wallet.core.fx.Currencies

object Notifications {
    const val CHANNEL_PAYMENTS = "payments"
    const val CHANNEL_LISTENER = "listener"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PAYMENTS, context.getString(R.string.notif_channel_payments), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LISTENER, context.getString(R.string.notif_channel_listener), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }

    fun openAppIntent(context: Context, route: String? = null): PendingIntent = PendingIntent.getActivity(
        context, route.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (route != null) putExtra("route", route) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opening the app means the payments were seen: drop them (and the launcher badge they keep). */
    fun clearPayments(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.channelId == CHANNEL_PAYMENTS }.forEach { nm.cancel(it.tag, it.id) }
    }

    fun showPayment(context: Context, notice: PaymentNotice) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val received = notice.type == "payment_received"
        val amount = (if (received) "+" else "−") + Currencies.formatSats(notice.tx.amountSats) + " sats"
        val title = context.getString(if (received) R.string.shared_tx_received else R.string.shared_tx_sent) + " · " + amount
        val n = NotificationCompat.Builder(context, CHANNEL_PAYMENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(notice.tx.description ?: context.getString(R.string.app_name))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, "activity/${notice.tx.paymentHash}"))
            .setColor(if (received) 0xFF22C55E.toInt() else 0xFFF97316.toInt())
            .build()
        NotificationManagerCompat.from(context).notify(notice.id.hashCode(), n)
    }
}

package com.jackh0006.xhr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object XhrNotifications {
    const val CHANNEL_ID = "xhr.status.v2"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        mode: ConnMode,
        title: String,
        text: String,
        big: String,
        serviceClass: Class<*>,
        stopAction: String,
    ): Notification {
        ensureChannel(context)
        val content = PendingIntent.getActivity(
            context,
            21,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            22,
            Intent(context, serviceClass).setAction(stopAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_xhr)
            .setColor(0xFF4285F4.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (mode == ConnMode.TUN) "XHR · TUN" else "XHR · PROXY")
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_stop), stop)
            .build()
    }

    fun update(context: Context, id: Int, notification: Notification) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }
}

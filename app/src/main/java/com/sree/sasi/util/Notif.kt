package com.sree.sasi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sree.sasi.MainActivity
import com.sree.sasi.R
import com.sree.sasi.overlay.CompanionService

object Notif {

    const val CHANNEL_ID = "companion_channel"
    const val SERVICE_NOTIF_ID = 1001
    private const val REMINDER_NOTIF_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun serviceNotification(context: Context): Notification {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, CompanionService::class.java).setAction(CompanionService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(context.getString(R.string.service_notif_title))
            .setContentText(context.getString(R.string.service_notif_text))
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_heart, context.getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showReminder(context: Context, title: String, text: String) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_NOTIF_ID, notification)
    }
}

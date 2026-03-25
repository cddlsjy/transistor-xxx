package org.y20k.transistor.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.y20k.transistor.R
import org.y20k.transistor.ui.PlayerActivity

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "transistor_playback_channel"
        const val NOTIFICATION_ID = 1
    }

    fun createNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 🔴 修复在这里：使用系统图标
        val smallIcon = android.R.drawable.ic_notification_media_play
        val stopIcon = android.R.drawable.ic_media_stop

        val stopIntent = Intent("com.y20k.transistor.STOP_PLAYBACK").let {
            PendingIntent.getBroadcast(
                context,
                1,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Playing")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(pendingIntent)
            .addAction(stopIcon, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Playback notification"
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
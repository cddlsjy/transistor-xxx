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

object NotificationHelper {

    private const val CHANNEL_ID = "transistor_playback_channel"
    const val NOTIFICATION_ID = 1001

    /**
     * 创建播放通知
     */
    fun createPlaybackNotification(context: Context, stationName: String): Notification {
        createNotificationChannel(context)

        // 点击通知打开播放器
        val intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 修复：使用项目正确的图标名（替换找不到的资源）
        val playIcon = R.drawable.ic_play_arrow
        val stopIcon = R.drawable.ic_stop

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transistor")
            .setContentText(stationName)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 修复：第64行语法错误 + 变量名错误
            .addAction(playIcon, "Play", createPlayPendingIntent(context))
            .addAction(stopIcon, "Stop", createStopPendingIntent(context))
            .setStyle(NotificationCompat.MediaStyle())
            .build()
    }

    /**
     * 创建通知渠道（Android O+ 必须）
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for radio playback"
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 播放/暂停 PendingIntent
     */
    private fun createPlayPendingIntent(context: Context): PendingIntent {
        val intent = Intent("PLAY_PAUSE").apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 停止 PendingIntent
     */
    private fun createStopPendingIntent(context: Context): PendingIntent {
        val intent = Intent("STOP").apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

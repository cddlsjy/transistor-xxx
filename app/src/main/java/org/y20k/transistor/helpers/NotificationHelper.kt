package org.y20k.transistor.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import org.y20k.transistor.Keys
import org.y20k.transistor.R
import org.y20k.transistor.core.Station
import org.y20k.transistor.playback.PlayerService

class NotificationHelper(
    private val context: Context,
    private val mediaSessionToken: MediaSessionCompat.Token
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "transistor_playback"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNotification(service: PlayerService, station: Station, metadata: String) {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
        val pi = PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_notification_app_icon_white_24dp)
            .setContentTitle(station.name)
            .setContentText(metadata)
            .setContentIntent(pi)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionToken)
            )
            .setOngoing(true)
            .build()

        service.startForeground(Keys.NOW_PLAYING_NOTIFICATION_ID, notification)
    }

    fun updateNotification() {}

    fun hideNotification(service: PlayerService) {
        notificationManager.cancel(Keys.NOW_PLAYING_NOTIFICATION_ID)
        service.stopForeground(true)
    }
}

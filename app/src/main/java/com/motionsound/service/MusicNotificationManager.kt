package com.motionsound.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession

object MusicNotificationManager {

    const val CHANNEL_ID = "playback"
    const val ACTION_PLAY_PAUSE = "com.motionsound.ACTION_PLAY_PAUSE"
    const val ACTION_SKIP_NEXT = "com.motionsound.ACTION_SKIP_NEXT"
    const val ACTION_SKIP_PREV = "com.motionsound.ACTION_SKIP_PREV"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        session: MediaSession,
        pendingIntent: PendingIntent,
        songTitle: String,
        artistName: String,
        albumArtUri: String?
    ): android.app.Notification {
        val player = session.player
        val playPauseIcon = if (player.isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        val playPauseIntent = PendingIntent.getService(
            context, 0,
            Intent(context, MusicService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val skipNextIntent = PendingIntent.getService(
            context, 1,
            Intent(context, MusicService::class.java).setAction(ACTION_SKIP_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val skipPrevIntent = PendingIntent.getService(
            context, 2,
            Intent(context, MusicService::class.java).setAction(ACTION_SKIP_PREV),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(songTitle)
            .setContentText(artistName)
            .setContentIntent(pendingIntent)
            .setStyle(
                NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", skipPrevIntent)
            .addAction(playPauseIcon, "Play / Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", skipNextIntent)
            .setOngoing(player.isPlaying)
            .setShowWhen(false)
            .build()
    }
}

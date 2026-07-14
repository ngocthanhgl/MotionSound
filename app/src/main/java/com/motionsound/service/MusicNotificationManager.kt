package com.motionsound.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat

object MusicNotificationManager {

    const val CHANNEL_ID = "playback_v2"
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
        session: MediaSessionCompat,
        pendingIntent: PendingIntent,
        songTitle: String,
        artistName: String,
        albumArtUri: String?,
        isPlaying: Boolean
    ): android.app.Notification {
        val playPauseIcon = if (isPlaying)
            com.motionsound.R.drawable.ic_notification_pause
        else
            com.motionsound.R.drawable.ic_notification_play

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

        val appIcon = loadAppIcon(context)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.motionsound.R.drawable.ic_launcher_foreground)
            .setContentTitle(songTitle)
            .setContentText(artistName)
            .setContentIntent(pendingIntent)
            .apply { appIcon?.let { setLargeIcon(it) } }
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(com.motionsound.R.drawable.ic_notification_skip_previous, "Previous", skipPrevIntent)
            .addAction(playPauseIcon, "Play / Pause", playPauseIntent)
            .addAction(com.motionsound.R.drawable.ic_notification_skip_next, "Next", skipNextIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .build()
    }

    private fun loadAppIcon(context: Context): Bitmap? {
        return try {
            val bg = android.graphics.drawable.ColorDrawable(0xFF15181D.toInt())
            val fg = context.getDrawable(com.motionsound.R.drawable.ic_launcher_foreground) ?: return null
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            bg.setBounds(0, 0, 128, 128)
            bg.draw(canvas)
            fg.setBounds(0, 0, 128, 128)
            fg.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}

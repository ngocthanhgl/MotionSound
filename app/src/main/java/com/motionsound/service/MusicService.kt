package com.motionsound.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.motionsound.MainActivity

class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                updateNotification()
            } else if (!player.playWhenReady && player.mediaItemCount == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicNotificationManager.createChannel(this)
        player = ExoPlayer.Builder(this).build().apply {
            addListener(playerListener)
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MusicNotificationManager.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
            }
            MusicNotificationManager.ACTION_SKIP_NEXT -> {
                player.seekToNextMediaItem()
            }
            MusicNotificationManager.ACTION_SKIP_PREV -> {
                player.seekToPreviousMediaItem()
            }
        }
        if (player.mediaItemCount > 0) updateNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private fun updateNotification() {
        val metadata = session.player.mediaMetadata

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = MusicNotificationManager.buildNotification(
            context = this,
            session = session,
            pendingIntent = pendingIntent,
            songTitle = metadata.title?.toString() ?: "MotionSound",
            artistName = metadata.artist?.toString() ?: "Unknown",
            albumArtUri = metadata.artworkUri?.toString()
        )

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
    }
}

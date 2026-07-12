package com.motionsound.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.motionsound.MainActivity

class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var isForeground = false
    private var pendingResume = false

    private val listeningTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val added = addedDevices.any { it.type in listeningTypes }
            if (added && pendingResume && player.mediaItemCount > 0) {
                pendingResume = false
                player.play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val removed = removedDevices.any { it.type in listeningTypes }
            if (removed && player.isPlaying) {
                pendingResume = true
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                pendingResume = false
                updateNotification()
            } else if (!player.playWhenReady && player.mediaItemCount == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                updateNotification()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Handler(Looper.getMainLooper()).postDelayed({ updateNotification() }, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicNotificationManager.createChannel(this)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .build()
            .apply { addListener(playerListener) }

        session = MediaSession.Builder(this, player).build()

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
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

        if (isForeground) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(audioDeviceCallback)
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
    }
}

package com.motionsound.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import com.motionsound.MainActivity
import com.motionsound.drive.AudioSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicService : android.app.Service() {

    lateinit var player: CustomPlayer
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private var noSongNotification: android.app.Notification? = null
    private var pendingResume = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null

    inner class PlayerBinder : Binder() {
        fun getPlayer(): CustomPlayer = player
    }

    private val binder = PlayerBinder()

    private val listeningTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val added = addedDevices.any { it.type in listeningTypes }
            if (added && pendingResume && player.state.value.currentIndex >= 0) {
                pendingResume = false
                player.play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val removed = removedDevices.any { it.type in listeningTypes }
            if (removed && player.state.value.isPlaying) {
                pendingResume = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        MusicNotificationManager.createChannel(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        noSongNotification = NotificationCompat.Builder(this, MusicNotificationManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MotionSound")
            .setContentText("Ready")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        startForeground(NOTIFICATION_ID, noSongNotification!!)

        player = CustomPlayer(this)

        AudioSessionStore.sessionId = player.audioSessionId

        mediaSession = MediaSessionCompat(this, "MotionSound")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { player.togglePlayPause() }
            override fun onPause() { player.pause() }
            override fun onSkipToNext() { player.playNext() }
            override fun onSkipToPrevious() { player.playPrevious() }
            override fun onSeekTo(pos: Long) { player.seekTo(pos) }
        })
        mediaSession.isActive = true

        stateJob = serviceScope.launch {
            try {
                player.state.collect { updateNotification() }
            } catch (_: Exception) {
            }
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MusicNotificationManager.ACTION_PLAY_PAUSE -> player.togglePlayPause()
            MusicNotificationManager.ACTION_SKIP_NEXT -> player.playNext()
            MusicNotificationManager.ACTION_SKIP_PREV -> player.playPrevious()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun updateNotification() {
        val state = player.state.value
        if (state.currentIndex < 0) {
            noSongNotification?.let {
                notificationManager.notify(NOTIFICATION_ID, it)
            }
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = MusicNotificationManager.buildNotification(
            context = this,
            session = mediaSession,
            pendingIntent = pendingIntent,
            songTitle = "MotionSound",
            artistName = "",
            albumArtUri = null,
            isPlaying = state.isPlaying
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.state.value.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaSession.isActive = false
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 100
    }
}

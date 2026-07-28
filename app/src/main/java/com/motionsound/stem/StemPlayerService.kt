package com.motionsound.stem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.motionsound.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerControlState(
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val songTitle: String? = null,
    val artistName: String? = null
)

class StemPlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): StemPlayerService = this@StemPlayerService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val mixer = StemMixer()
    private val decoder = AudioDecoder(this)
    @Volatile private var engine: StemSeparationEngine? = null
    private val cache = StemCache(this)
    private var sensorMapper: SensorDriveMapper? = null

    private var currentStems: StemResult? = null
    private var currentPlaylist = listOf<String>()
    private var currentIndex = -1
    private var pendingResume = false

    private val _playerState = MutableStateFlow(PlayerControlState())
    val playerState: StateFlow<PlayerControlState> = _playerState.asStateFlow()

    private val _stemState = MutableStateFlow(StemUiState())
    val stemState: StateFlow<StemUiState> = _stemState.asStateFlow()

    private val _modelLoadState = MutableStateFlow(ModelLoadState.LOADING)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    private val _separationProgress = MutableStateFlow(0f)
    val separationProgress: StateFlow<Float> = _separationProgress.asStateFlow()

    private var loadJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private var hasAudioFocus = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                mixer.masterVolume = 1.0f
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                mixer.masterVolume = 1.0f
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mixer.masterVolume = 0.2f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                mixer.masterVolume = 1.0f
            }
        }
    }

    private val listeningTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val added = addedDevices.any { it.type in listeningTypes }
            if (added && pendingResume && _playerState.value.currentIndex >= 0) {
                pendingResume = false
                play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val removed = removedDevices.any { it.type in listeningTypes }
            if (removed && _playerState.value.isPlaying) {
                pendingResume = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionSound:StemPlayer")
        wakeLock?.acquire()

        mixer.prepare()

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting"))
        } catch (e: Exception) {
            Log.w("StemPlayerService", "startForeground failed", e)
        }

        scope.launch(Dispatchers.IO) {
            val e = StemSeparationEngine(this@StemPlayerService)
            val loaded = e.initialize()
            engine = e
            _modelLoadState.value = if (loaded) ModelLoadState.LOADED else ModelLoadState.ERROR
            updateNotification("Ready")
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        sensorMapper = SensorDriveMapper(this, mixer) { state ->
            _stemState.value = state
        }
        sensorMapper?.start()
        sensorMapper?.enableGpsSpeed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_SKIP_NEXT -> playNext()
            ACTION_SKIP_PREV -> playPrevious()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        sensorMapper?.stop()
        engine?.release()
        mixer.release()
        abandonAudioFocus()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    fun loadSong(uri: Uri) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _separationProgress.value = 0f
            updateNotification("Decoding audio…")
            _stemState.value = _stemState.value.copy(separationProgress = 0f)

            val pcm = withContext(Dispatchers.IO) { decoder.decode(uri) }
            if (pcm == null) {
                updateNotification("Decode failed")
                return@launch
            }
            _separationProgress.value = 0.1f

            val cached = withContext(Dispatchers.IO) {
                if (cache.hasCachedStems(uri)) cache.loadStems(uri) else null
            }

            if (cached != null) {
                currentStems = cached
                _separationProgress.value = 1f
                _stemState.value = _stemState.value.copy(modelLoaded = true)
                updateNotification("Ready")
                mixer.play(cached, scope)
                _playerState.value = _playerState.value.copy(isPlaying = true)
                requestAudioFocus()
                return@launch
            }

            val e = engine
            if (e == null || !e.isLoaded()) {
                _stemState.value = _stemState.value.copy(modelLoaded = false)
                updateNotification("Model not loaded")
                return@launch
            }

            updateNotification("Separating stems…")
            val result = e.separate(pcm) { progress ->
                val overall = 0.1f + progress * 0.85f
                _separationProgress.value = overall
                _stemState.value = _stemState.value.copy(separationProgress = overall)
            }

            if (result == null) {
                updateNotification("Separation failed")
                return@launch
            }

            withContext(Dispatchers.IO) { cache.saveStems(uri, result) }

            currentStems = result
            _separationProgress.value = 1f
            _stemState.value = _stemState.value.copy(modelLoaded = true, separationProgress = 1f)
            updateNotification("Playing")

            mixer.play(result, scope)
            _playerState.value = _playerState.value.copy(isPlaying = true)
            requestAudioFocus()
        }
    }

    fun setPlaylist(uris: List<String>, startIndex: Int) {
        currentPlaylist = uris
        playAt(startIndex)
    }

    private fun playAt(index: Int) {
        if (index !in currentPlaylist.indices) return
        loadJob?.cancel()
        stopInternal()
        currentIndex = index
        _playerState.value = PlayerControlState(currentIndex = index)
        loadSong(Uri.parse(currentPlaylist[index]))
    }

    fun play() {
        if (currentStems == null) return
        mixer.play(currentStems!!, scope, (mixer.getPlaybackPositionSeconds() * StemConfig.SAMPLE_RATE).toInt())
        _playerState.value = _playerState.value.copy(isPlaying = true)
        requestAudioFocus()
    }

    fun pause() {
        mixer.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        abandonAudioFocus()
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        mixer.stop()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        abandonAudioFocus()
    }

    fun togglePlayPause() {
        if (_playerState.value.isPlaying) pause() else play()
    }

    fun playNext() {
        if (currentIndex + 1 in currentPlaylist.indices) playAt(currentIndex + 1)
    }

    fun playPrevious() {
        if (currentIndex - 1 in currentPlaylist.indices) playAt(currentIndex - 1)
    }

    fun seekTo(positionMs: Long) {
        if (currentStems == null) return
        val frame = (positionMs * StemConfig.SAMPLE_RATE / 1000L).toInt()
        mixer.seekToFrame(frame, currentStems!!, scope)
    }

    fun getCurrentPosition(): Long {
        return (mixer.getPlaybackPositionSeconds() * 1000f).toLong()
    }

    fun hasNext(): Boolean = currentIndex + 1 in currentPlaylist.indices
    fun hasPrevious(): Boolean = currentIndex - 1 in currentPlaylist.indices

    fun setMetadata(title: String?, artist: String?) {
        _playerState.value = _playerState.value.copy(songTitle = title, artistName = artist)
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = audioManager.requestAudioFocus(
            audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocus(audioFocusListener)
        hasAudioFocus = false
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackChannel = NotificationChannel(
                CHANNEL_ID, "Stem Player", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(playbackChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val state = _playerState.value
        val playPauseIcon = if (state.isPlaying)
            com.motionsound.R.drawable.ic_notification_pause
        else
            com.motionsound.R.drawable.ic_notification_play

        val playPauseIntent = PendingIntent.getService(
            this, 0,
            Intent(this, StemPlayerService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val skipNextIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StemPlayerService::class.java).setAction(ACTION_SKIP_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val skipPrevIntent = PendingIntent.getService(
            this, 2,
            Intent(this, StemPlayerService::class.java).setAction(ACTION_SKIP_PREV),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.motionsound.R.drawable.ic_launcher_foreground)
            .setContentTitle(state.songTitle ?: "MotionSound")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                com.motionsound.R.drawable.ic_notification_skip_previous,
                "Previous", skipPrevIntent
            )
            .addAction(playPauseIcon, "Play / Pause", playPauseIntent)
            .addAction(
                com.motionsound.R.drawable.ic_notification_skip_next,
                "Next", skipNextIntent
            )
            .setOngoing(state.isPlaying)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!_playerState.value.isPlaying) stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 100
        const val CHANNEL_ID = "stem_player_channel"
        const val ACTION_PLAY_PAUSE = "com.motionsound.ACTION_PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "com.motionsound.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREV = "com.motionsound.ACTION_SKIP_PREV"
    }
}

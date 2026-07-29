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
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.motionsound.MainActivity
import com.motionsound.sounddrive.SensorProfile
import com.motionsound.sounddrive.SoundDriveConfig
import com.motionsound.sounddrive.SoundDriveMode
import com.motionsound.sounddrive.SoundDriveProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

data class PreCacheProgress(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0
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
    private var soundDriveProcessor: SoundDriveProcessor? = null

    var soundDriveEnabled: Boolean
        get() = sensorMapper?.soundDriveConfig?.enabled ?: false
        set(v) { sensorMapper?.let { it.soundDriveConfig = it.soundDriveConfig.copy(enabled = v) } }

    var soundDriveMode: SoundDriveMode
        get() = sensorMapper?.soundDriveConfig?.mode ?: SoundDriveMode.DYNAMIC
        set(v) { sensorMapper?.let { it.soundDriveConfig = it.soundDriveConfig.copy(mode = v) } }

    var soundDriveIntensity: Float
        get() = sensorMapper?.soundDriveConfig?.intensity ?: 0.7f
        set(v) { sensorMapper?.let { it.soundDriveConfig = it.soundDriveConfig.copy(intensity = v.coerceIn(0f, 1f)) } }

    var sensorProfile: SensorProfile
        get() = sensorMapper?.soundDriveConfig?.sensorProfile ?: SensorProfile.DYNAMIC
        set(v) { sensorMapper?.let { it.soundDriveConfig = it.soundDriveConfig.copy(sensorProfile = v) } }

    fun setCustomFilterSweep(v: Float) {
        sensorMapper?.let {
            val p = it.soundDriveConfig.customParams
            it.soundDriveConfig = it.soundDriveConfig.copy(customParams = p.copy(masterCutoff = 0.4f + v.coerceIn(0f, 1f) * 0.6f))
        }
    }
    fun setCustomPanDepth(v: Float) {
        sensorMapper?.let {
            val p = it.soundDriveConfig.customParams
            it.soundDriveConfig = it.soundDriveConfig.copy(customParams = p.copy(otherPan = v.coerceIn(0f, 1f) * 0.5f))
        }
    }
    fun setCustomAtmosphere(v: Float) {
        sensorMapper?.let {
            val p = it.soundDriveConfig.customParams
            it.soundDriveConfig = it.soundDriveConfig.copy(customParams = p.copy(otherBoost = 0.5f + v.coerceIn(0f, 1f) * 1.5f))
        }
    }
    fun setCustomLowCut(v: Float) {
        sensorMapper?.let {
            val p = it.soundDriveConfig.customParams
            it.soundDriveConfig = it.soundDriveConfig.copy(customParams = p.copy(masterLowCut = v.coerceIn(0f, 1f) * 0.01f))
        }
    }

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
    private var preCacheJob: Job? = null

    private val _preCacheProgress = MutableStateFlow(PreCacheProgress())
    val preCacheProgress: StateFlow<PreCacheProgress> = _preCacheProgress.asStateFlow()

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
        AppLogger.event("StemSvc", "SVC_CREATE")

        createChannels()
        AppLogger.event("StemSvc", "CHANNELS_OK")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionSound:StemPlayer")
            wakeLock?.acquire()
            AppLogger.event("StemSvc", "WAKELOCK_OK")
        } catch (e: Exception) {
            AppLogger.w("StemSvc", "Wakelock failed: ${e.message}")
        }

        try {
            mixer.prepare()
            AppLogger.event("StemSvc", "MIXER_PREPARE_OK")
        } catch (e: Exception) {
            AppLogger.error("StemSvc", "Mixer prepare failed", e)
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting"))
            AppLogger.event("StemSvc", "FOREGROUND_OK")
        } catch (e: Exception) {
            AppLogger.error("StemSvc", "Foreground start failed", e)
        }

        scope.launch(Dispatchers.IO) {
            try {
                AppLogger.event("StemSvc", "INIT_START")
                val e = StemSeparationEngine(this@StemPlayerService)
                val loaded = e.initialize { progress ->
                    _stemState.value = _stemState.value.copy(downloadProgress = progress)
                }
                engine = e
                _modelLoadState.value = if (loaded) ModelLoadState.LOADED else ModelLoadState.ERROR
                if (loaded) {
                    _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null, downloadProgress = 0f)
                    AppLogger.event("StemSvc", "INIT_DONE", "model loaded")
                } else {
                    _stemState.value = _stemState.value.copy(modelLoaded = false, modelError = e.lastError, downloadProgress = 0f)
                    AppLogger.w("StemSvc", "INIT_FAILED: ${e.lastError ?: "unknown"}")
                }
                updateNotification("Ready")
            } catch (e: Throwable) {
                AppLogger.error("StemSvc", "INIT_CRASHED", e)
                _modelLoadState.value = ModelLoadState.ERROR
                _stemState.value = _stemState.value.copy(
                    modelLoaded = false,
                    modelError = "${e::class.simpleName}: ${e.message ?: "(no message)"}"
                )
            }
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        soundDriveProcessor = SoundDriveProcessor(mixer)
        sensorMapper = SensorDriveMapper(this, mixer) { state ->
            _stemState.value = _stemState.value.copy(
                speed = state.speed,
                speedKmh = state.speedKmh,
                accelIntensity = state.accelIntensity,
                brakeIntensity = state.brakeIntensity,
                cornerIntensity = state.cornerIntensity,
                drivingState = state.drivingState,
                volumeDrums = state.volumeDrums,
                volumeBass = state.volumeBass,
                volumeOther = state.volumeOther,
                volumeVocals = state.volumeVocals,
                soundDriveEnabled = state.soundDriveEnabled,
                soundDriveMode = state.soundDriveMode,
                soundDriveIntensity = state.soundDriveIntensity,
                gestureIndicator = state.gestureIndicator,
                roadRoughness = state.roadRoughness,
                ambientMood = state.ambientMood,
                hillGrade = state.hillGrade,
                brakeType = state.brakeType,
                sensorProfile = state.sensorProfile
            )
        }
        sensorMapper?.soundDriveProcessor = soundDriveProcessor
        sensorMapper?.start()
        sensorMapper?.enableGpsSpeed()
        AppLogger.event("StemSvc", "SENSORS_STARTED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        AppLogger.event("StemSvc", "START_COMMAND", "action=$action")
        when (action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_SKIP_NEXT -> playNext()
            ACTION_SKIP_PREV -> playPrevious()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        AppLogger.event("StemSvc", "SVC_BIND")
        return binder
    }

    override fun onDestroy() {
        AppLogger.event("StemSvc", "SVC_DESTROY")
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
        AppLogger.event("StemSvc", "LOAD_SONG", uri.lastPathSegment ?: uri.toString())
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                _separationProgress.value = 0f
                updateNotification("Decoding audio…")
                _stemState.value = _stemState.value.copy(separationProgress = 0f)
                AppLogger.event("StemSvc", "DECODE_START")

                val pcm = withContext(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    val result = decoder.decode(uri)
                    val elapsed = System.currentTimeMillis() - startMs
                    AppLogger.i("StemSvc", "Decoded ${result?.size?.div(2)} frames in ${elapsed}ms (${result?.let { it.size * 4L / 1024 / 1024 } ?: 0} MB)")
                    result
                }
                if (pcm == null) {
                    AppLogger.w("StemSvc", "DECODE_FAILED")
                    updateNotification("Decode failed")
                    return@launch
                }
                _separationProgress.value = 0.1f
                AppLogger.event("StemSvc", "CHECK_CACHE")

                val cached = withContext(Dispatchers.IO) {
                    if (cache.hasCachedStems(uri)) cache.loadStems(uri) else null
                }

                if (cached != null) {
                    AppLogger.event("StemSvc", "CACHE_HIT")
                    currentStems = cached
                    _separationProgress.value = 1f
                    _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null)
                    updateNotification("Ready")
                    mixer.play(cached, scope)
                    _playerState.value = _playerState.value.copy(isPlaying = true)
                    requestAudioFocus()
                    return@launch
                }
                AppLogger.event("StemSvc", "CACHE_MISS")

                val e = engine
                if (e == null || !e.isLoaded()) {
                    AppLogger.w("StemSvc", "ENGINE_NOT_LOADED")
                    updateNotification("Model not loaded yet")
                    return@launch
                }

                AppLogger.event("StemSvc", "SEPARATE_START", "pcm=${pcm.size} floats")
                updateNotification("Separating stems…")
                val separateStartMs = System.currentTimeMillis()
                val result = e.separate(pcm) { progress ->
                    val overall = 0.1f + progress * 0.85f
                    _separationProgress.value = overall
                    _stemState.value = _stemState.value.copy(separationProgress = overall)
                }
                val separateElapsed = System.currentTimeMillis() - separateStartMs
                AppLogger.event("StemSvc", "SEPARATE_DONE", "${separateElapsed}ms")

                if (result == null) {
                    AppLogger.w("StemSvc", "SEPARATE_FAILED")
                    updateNotification("Separation failed")
                    return@launch
                }

                AppLogger.event("StemSvc", "CACHE_SAVE")
                withContext(Dispatchers.IO) { cache.saveStems(uri, result) }

                currentStems = result
                _separationProgress.value = 1f
                _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null, separationProgress = 1f)
                updateNotification("Playing")

                mixer.play(result, scope)
                _playerState.value = _playerState.value.copy(isPlaying = true)
                requestAudioFocus()
                AppLogger.event("StemSvc", "PLAYBACK_STARTED")
            } catch (e: Throwable) {
                val msg = "${e::class.simpleName}: ${e.message}"
                AppLogger.error("StemSvc", "LOAD_SONG_CRASHED: $msg", e)
                _stemState.value = _stemState.value.copy(modelError = msg, modelLoaded = false)
                updateNotification("Error: $msg")
            }
        }
    }

    fun preCachePlaylist(uris: List<String>) {
        preCacheJob?.cancel()
        preCacheJob = scope.launch(Dispatchers.IO) {
            val toCache = uris.filter { !cache.hasCachedStems(Uri.parse(it)) }
            if (toCache.isEmpty()) return@launch

            val total = toCache.size
            _preCacheProgress.value = PreCacheProgress(isRunning = true, total = total)

            for ((i, uri) in toCache.withIndex()) {
                if (!isActive) break
                val current = currentPlaylist.getOrNull(currentIndex)
                if (uri == current) continue

                try {
                    val pcm = decoder.decode(Uri.parse(uri)) ?: continue
                    val e = engine ?: continue
                    val result = e.separate(pcm) ?: continue
                    cache.saveStems(Uri.parse(uri), result)
                    _preCacheProgress.value = _preCacheProgress.value.copy(completed = i + 1)
                } catch (_: Exception) {
                    _preCacheProgress.value = _preCacheProgress.value.copy(
                        failed = _preCacheProgress.value.failed + 1
                    )
                }
            }

            _preCacheProgress.value = PreCacheProgress()
        }
    }

    fun cancelPreCache() {
        preCacheJob?.cancel()
        _preCacheProgress.value = PreCacheProgress()
    }

    fun setPlaylist(uris: List<String>, startIndex: Int) {
        AppLogger.event("StemSvc", "SET_PLAYLIST", "count=${uris.size} start=$startIndex")
        currentPlaylist = uris
        playAt(startIndex)
    }

    private fun playAt(index: Int) {
        if (index !in currentPlaylist.indices) return
        AppLogger.event("StemSvc", "PLAY_AT", "index=$index")
        loadJob?.cancel()
        stopInternal()
        currentIndex = index
        _playerState.value = PlayerControlState(currentIndex = index)
        loadSong(Uri.parse(currentPlaylist[index]))
    }

    fun play() {
        AppLogger.event("StemSvc", "PLAY")
        if (currentStems == null) return
        mixer.play(currentStems!!, scope, (mixer.getPlaybackPositionSeconds() * StemConfig.SAMPLE_RATE).toInt())
        _playerState.value = _playerState.value.copy(isPlaying = true)
        requestAudioFocus()
    }

    fun pause() {
        AppLogger.event("StemSvc", "PAUSE")
        mixer.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        abandonAudioFocus()
    }

    fun stop() {
        AppLogger.event("StemSvc", "STOP")
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
        AppLogger.event("StemSvc", "PLAY_NEXT", "index=${currentIndex + 1}")
        if (currentIndex + 1 in currentPlaylist.indices) playAt(currentIndex + 1)
    }

    fun playPrevious() {
        AppLogger.event("StemSvc", "PLAY_PREV", "index=${currentIndex - 1}")
        if (currentIndex - 1 in currentPlaylist.indices) playAt(currentIndex - 1)
    }

    fun seekTo(positionMs: Long) {
        AppLogger.event("StemSvc", "SEEK_TO", "${positionMs}ms")
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

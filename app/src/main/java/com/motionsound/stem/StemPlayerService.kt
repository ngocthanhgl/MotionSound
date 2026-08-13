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
import com.motionsound.data.SoundPrefsStore
import com.motionsound.sounddrive.SoundDriveConfig
import com.motionsound.sounddrive.SoundDriveMode
import com.motionsound.sounddrive.SoundDriveProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    val failed: Int = 0,
    val fraction: Float = 0f
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
    @Volatile private var batchUri: String? = null
    private var preCacheSeq = 0
    @Volatile private var loadingUri: String? = null
    private var sensorMapper: SensorDriveMapper? = null

    private var currentStems: StemResult? = null
    private var soundDriveProcessor: SoundDriveProcessor? = null

    var soundDriveEnabled: Boolean
        get() = sensorMapper?.soundDriveConfig?.enabled ?: pendingSoundDriveConfig?.enabled ?: false
        set(v) { updateSoundDriveConfig { it.copy(enabled = v) } }

    var soundDriveMode: SoundDriveMode
        get() = sensorMapper?.soundDriveConfig?.mode ?: pendingSoundDriveConfig?.mode ?: SoundDriveMode.DYNAMIC
        set(v) { updateSoundDriveConfig { it.copy(mode = v) } }

    var soundDriveIntensity: Float
        get() = sensorMapper?.soundDriveConfig?.intensity ?: pendingSoundDriveConfig?.intensity ?: 0.7f
        set(v) { updateSoundDriveConfig { it.copy(intensity = v.coerceIn(0f, 1f)) } }

    var soundDriveGpsMode: Boolean
        get() = sensorMapper?.soundDriveConfig?.gpsMode ?: pendingSoundDriveConfig?.gpsMode ?: false
        set(v) { updateSoundDriveConfig { it.copy(gpsMode = v) } }

    private var pendingSoundDriveConfig: SoundDriveConfig? = null

    private fun updateSoundDriveConfig(transform: (SoundDriveConfig) -> SoundDriveConfig) {
        val mapper = sensorMapper
        val cfg = if (mapper != null) {
            transform(mapper.soundDriveConfig).also { mapper.soundDriveConfig = it }
        } else {
            transform(pendingSoundDriveConfig ?: SoundDriveConfig()).also { pendingSoundDriveConfig = it }
        }
        if (cfg.enabled) acquireWakeLock() else releaseWakeLock()
        persistPrefs(cfg)
    }

    private fun persistPrefs(cfg: SoundDriveConfig) {
        val p = soundDriveProcessor
        scope.launch(Dispatchers.IO) {
            runCatching {
                SoundPrefsStore.save(
                    this@StemPlayerService,
                    SoundPrefsStore.StoredPrefs(
                        config = cfg,
                        loopMode = loopMode,
                        volumeDrums = p?.manualDrums ?: mixer.volumeDrums,
                        volumeBass = p?.manualBass ?: mixer.volumeBass,
                        volumeOther = p?.manualOther ?: mixer.volumeOther,
                        volumeVocals = p?.manualVocals ?: mixer.volumeVocals
                    )
                )
            }.onFailure { e ->
                AppLogger.w("StemSvc", "PREFS_SAVE_FAILED: ${e.message}")
            }
        }
    }

    private var currentPlaylist = listOf<String>()
    private var currentIndex = -1
    private var pendingResume = false
    @Volatile var loopMode = false

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
    private var processJob: Job? = null

    private val _preCacheProgress = MutableStateFlow(PreCacheProgress())
    val preCacheProgress: StateFlow<PreCacheProgress> = _preCacheProgress.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockCount = 0

    @Synchronized
    private fun acquireWakeLock() {
        wakeLockCount++
        if (wakeLockCount > 1) return
        val wl = wakeLock
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionSound:StemPlayer")
        }
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            AppLogger.w("StemSvc", "Wakelock acquire failed: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLockCount--
        if (wakeLockCount > 0) return
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            AppLogger.w("StemSvc", "Wakelock release failed: ${e.message}")
        }
    }
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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionSound:StemPlayer")
        } catch (e: Exception) {
            AppLogger.w("StemSvc", "Wakelock failed: ${e.message}")
        }

        try {
            mixer.prepare()
        } catch (e: Exception) {
            AppLogger.error("StemSvc", "Mixer prepare failed", e)
        }

        mixer.onTrackEnded = { handleTrackFinished() }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting"))
        } catch (e: Exception) {
            AppLogger.error("StemSvc", "Foreground start failed", e)
        }

        scope.launch(Dispatchers.IO) {
            try {
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
                gpsMode = state.gpsMode,
                gpsStatus = state.gpsStatus,
                maxSpeedKmh = state.maxSpeedKmh,
                manualVolumeDrums = soundDriveProcessor?.manualDrums ?: 1f,
                manualVolumeBass = soundDriveProcessor?.manualBass ?: 1f,
                manualVolumeOther = soundDriveProcessor?.manualOther ?: 1f,
                manualVolumeVocals = soundDriveProcessor?.manualVocals ?: 1f
            )
        }
        sensorMapper?.soundDriveProcessor = soundDriveProcessor
        sensorMapper?.start()
        sensorMapper?.enableGpsSpeed()
        pendingSoundDriveConfig?.let { sensorMapper?.soundDriveConfig = it }
        pendingSoundDriveConfig = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                SoundPrefsStore.load(this@StemPlayerService)
            }.onSuccess { prefs ->
                if (prefs != null) {
                    sensorMapper?.soundDriveConfig = prefs.config
                    loopMode = prefs.loopMode
                    soundDriveProcessor?.let { p ->
                        p.manualDrums = prefs.volumeDrums
                        p.manualBass = prefs.volumeBass
                        p.manualOther = prefs.volumeOther
                        p.manualVocals = prefs.volumeVocals
                    }
                    if (!prefs.config.enabled) {
                        mixer.volumeDrums = prefs.volumeDrums
                        mixer.volumeBass = prefs.volumeBass
                        mixer.volumeOther = prefs.volumeOther
                        mixer.volumeVocals = prefs.volumeVocals
                    }
                    AppLogger.event("StemSvc", "PREFS_RESTORED", "mode=${prefs.config.mode} intensity=${prefs.config.intensity}")
                    if (prefs.config.enabled) acquireWakeLock()
                }
            }.onFailure { e ->
                AppLogger.w("StemSvc", "PREFS_RESTORE_FAILED: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        when (action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_SKIP_NEXT -> playNext()
            ACTION_SKIP_PREV -> playPrevious()
        }
        sensorMapper?.enableGpsSpeed()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

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
        val uriStr = uri.toString()
        if (loadJob?.isActive == true && loadingUri == uriStr) {
            return
        }
        loadingUri = uriStr
        loadJob?.cancel()
        loadJob = scope.launch {
            acquireWakeLock()
            try {
                _separationProgress.value = 0f
                updateNotification("Decoding audio…")
                _stemState.value = _stemState.value.copy(separationProgress = 0f)

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
                    releaseWakeLock()
                    return@launch
                }
                _separationProgress.value = 0.1f

                val cached = withContext(Dispatchers.IO) {
                    if (cache.hasCachedStems(uri)) cache.loadStems(uri) else null
                }

                if (cached != null) {
                    currentStems = cached
                    _separationProgress.value = 1f
                    _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null)
                    updateNotification("Ready")
                    prepareBeatGrid(cached)
                    mixer.play(cached, scope)
                    _playerState.value = _playerState.value.copy(
                        isPlaying = true,
                        durationMs = cached.frameCount * 1000L / StemConfig.SAMPLE_RATE
                    )
                    requestAudioFocus()
                    return@launch
                }

                while (batchUri != null && batchUri == uri.toString()
                    && (processJob?.isActive == true || preCacheJob?.isActive == true)
                ) {
                    delay(500)
                }
                if (cache.hasCachedStems(uri)) {
                    val cachedLate = cache.loadStems(uri)
                    if (cachedLate != null) {
                        currentStems = cachedLate
                        _separationProgress.value = 1f
                        _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null)
                        updateNotification("Ready")
                        prepareBeatGrid(cachedLate)
                        mixer.play(cachedLate, scope)
                        _playerState.value = _playerState.value.copy(
                            isPlaying = true,
                            durationMs = cachedLate.frameCount * 1000L / StemConfig.SAMPLE_RATE
                        )
                        requestAudioFocus()
                        return@launch
                    }
                }

                val e = engine
                if (e == null || !e.isLoaded()) {
                    AppLogger.w("StemSvc", "ENGINE_NOT_LOADED")
                    updateNotification("Model not loaded yet")
                    releaseWakeLock()
                    return@launch
                }

                updateNotification("Separating stems…")
                val separateStartMs = System.currentTimeMillis()
                val result = e.separate(pcm) { progress ->
                    val overall = 0.1f + progress * 0.85f
                    _separationProgress.value = overall
                    _stemState.value = _stemState.value.copy(separationProgress = overall)
                }
                val separateElapsed = System.currentTimeMillis() - separateStartMs

                if (result == null) {
                    AppLogger.w("StemSvc", "SEPARATE_FAILED")
                    updateNotification("Separation failed")
                    releaseWakeLock()
                    return@launch
                }

                withContext(Dispatchers.IO) { cache.saveStems(uri, result) }

currentStems = result
                _separationProgress.value = 1f
                _stemState.value = _stemState.value.copy(modelLoaded = true, modelError = null, separationProgress = 1f)
                updateNotification("Playing")
                prepareBeatGrid(result)
                mixer.play(result, scope)
                _playerState.value = _playerState.value.copy(
                    isPlaying = true,
                    durationMs = result.frameCount * 1000L / StemConfig.SAMPLE_RATE
                )
                requestAudioFocus()
            } catch (e: CancellationException) {
                releaseWakeLock()
                throw e
            } catch (e: Throwable) {
                val msg = "${e::class.simpleName}: ${e.message}"
                AppLogger.error("StemSvc", "LOAD_SONG_CRASHED: $msg", e)
                _stemState.value = _stemState.value.copy(modelError = msg)
                releaseWakeLock()
                updateNotification("Error: $msg")
            }
        }
    }

    private suspend fun prepareBeatGrid(result: StemResult) {
        val analysis = withContext(Dispatchers.IO) { StemAnalyzer.analyze(result) }
        mixer.setBeatGrid(analysis)
    }

    fun preCachePlaylist(uris: List<String>) {
        if (preCacheJob?.isActive == true) {
            return
        }
        processJob?.cancel()
        val seq = ++preCacheSeq
        val batchScope = scope.launch(Dispatchers.IO) {
            acquireWakeLock()
            try {
                if (engine?.isLoaded() != true) {
                    updateNotification("Model not loaded yet")
                    return@launch
                }
                val toCache = uris.filter { !cache.hasCachedStems(Uri.parse(it)) }
                if (toCache.isEmpty()) return@launch

                val total = toCache.size
                _preCacheProgress.value = PreCacheProgress(isRunning = true, total = total)
                _separationProgress.value = 0f
                _stemState.value = _stemState.value.copy(separationProgress = 0f)

                for ((i, uri) in toCache.withIndex()) {
                    if (!isActive) break
                    val current = currentPlaylist.getOrNull(currentIndex)
                    if (uri == current) continue

                    val uriObj = Uri.parse(uri)
                    while (loadJob?.isActive == true && loadingUri == uri && isActive) {
                        delay(200)
                    }
                    if (!isActive) break
                    if (cache.hasCachedStems(uriObj)) continue
                    if (loadJob?.isActive == true) {
                        continue
                    }

                    try {
                        updateNotification("Separating stems ${i + 1}/$total…")
                        val pcm = decoder.decode(uriObj) ?: continue
                        val e = engine ?: continue
                        batchUri = uri
                        try {
                            e.throttled = mixer.isPlaying()
                            val result = e.separate(pcm) { progress ->
                                val overall = i.toFloat() / total + progress / total
                                _separationProgress.value = overall
                                _stemState.value = _stemState.value.copy(separationProgress = overall)
                                _preCacheProgress.value = _preCacheProgress.value.copy(
                                    completed = i, fraction = progress
                                )
                            } ?: continue
                            cache.saveStems(uriObj, result)
                            _preCacheProgress.value = _preCacheProgress.value.copy(completed = i + 1)
                        } finally {
                            e.throttled = false
                            batchUri = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _preCacheProgress.value = _preCacheProgress.value.copy(
                            failed = _preCacheProgress.value.failed + 1
                        )
                    }
                }
            } finally {
                releaseWakeLock()
                if (preCacheSeq == seq) _preCacheProgress.value = PreCacheProgress()
                _separationProgress.value = 0f
                _stemState.value = _stemState.value.copy(separationProgress = 0f)
            }
        }
        preCacheJob = batchScope
    }

    fun cancelPreCache() {
        preCacheJob?.cancel()
        _preCacheProgress.value = PreCacheProgress()
    }

    fun processPlaylist(uris: List<String>) {
        if (processJob?.isActive == true) {
            return
        }
        preCacheJob?.cancel()
        val seq = ++preCacheSeq
        processJob = scope.launch(Dispatchers.IO) {
            acquireWakeLock()
            try {
                if (engine?.isLoaded() != true) {
                    updateNotification("Model not loaded yet")
                    return@launch
                }
                val uncached = uris.filter { !cache.hasCachedStems(Uri.parse(it)) }
                if (uncached.isEmpty()) return@launch
                val baseCompleted = uris.size - uncached.size
                _preCacheProgress.value = PreCacheProgress(isRunning = true, total = uris.size, completed = baseCompleted)
                val baseFraction = baseCompleted.toFloat() / uris.size
                _separationProgress.value = baseFraction
                _stemState.value = _stemState.value.copy(separationProgress = baseFraction)
                val markDone: (Int) -> Unit = { i ->
                    _preCacheProgress.value = _preCacheProgress.value.copy(
                        completed = baseCompleted + i + 1, fraction = 0f
                    )
                    _separationProgress.value = (baseCompleted + i + 1).toFloat() / uris.size
                    _stemState.value = _stemState.value.copy(separationProgress = _separationProgress.value)
                }
                for ((i, uri) in uncached.withIndex()) {
                    if (!isActive) break
                    val current = currentPlaylist.getOrNull(currentIndex)
                    if (uri == current) {
                        markDone(i)
                        continue
                    }

                    val uriObj = Uri.parse(uri)
                    while (loadJob?.isActive == true && loadingUri == uri && processJob?.isActive == true) {
                        delay(200)
                    }
                    if (!isActive) break
                    if (cache.hasCachedStems(uriObj)) {
                        markDone(i)
                        continue
                    }
                    if (loadJob?.isActive == true) {
                        markDone(i)
                        continue
                    }
                    try {
                        updateNotification("Separating stems ${i + 1}/${uris.size}…")
                        val pcm = decoder.decode(uriObj)
                        if (pcm == null) {
                            markDone(i)
                            continue
                        }
                        val e = engine
                        if (e == null) {
                            markDone(i)
                            continue
                        }
                        batchUri = uri
                        try {
                            e.throttled = mixer.isPlaying()
                            val result = e.separate(pcm) { progress ->
                                val overall = (baseCompleted + i).toFloat() / uris.size + progress / uris.size
                                _separationProgress.value = overall
                                _stemState.value = _stemState.value.copy(separationProgress = overall)
                                _preCacheProgress.value = _preCacheProgress.value.copy(
                                    completed = baseCompleted + i, fraction = progress
                                )
                            }
                            if (result == null) {
                                markDone(i)
                                continue
                            }
                            cache.saveStems(uriObj, result)
                        } finally {
                            e.throttled = false
                            batchUri = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                    markDone(i)
                }
            } finally {
                releaseWakeLock()
                if (preCacheSeq == seq) {
                    _preCacheProgress.value = PreCacheProgress(isRunning = true, total = uris.size, completed = uris.size)
                    _separationProgress.value = 1f
                    _stemState.value = _stemState.value.copy(separationProgress = 1f)
                    if (isActive) runCatching { delay(400) }
                    _preCacheProgress.value = PreCacheProgress()
                }
                _separationProgress.value = 0f
                _stemState.value = _stemState.value.copy(separationProgress = 0f)
            }
        }
    }

    fun cancelProcessing() {
        processJob?.cancel()
        _preCacheProgress.value = PreCacheProgress()
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
        if (currentStems == null) {
            if (currentIndex in currentPlaylist.indices) {
                playAt(currentIndex)
            }
            return
        }
        if (_playerState.value.isPlaying) return
        acquireWakeLock()
        mixer.play(currentStems!!, scope, (mixer.getPlaybackPositionSeconds() * StemConfig.SAMPLE_RATE).toInt())
        _playerState.value = _playerState.value.copy(isPlaying = true)
        requestAudioFocus()
    }

    fun pause() {
        if (!_playerState.value.isPlaying) return
        mixer.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        releaseWakeLock()
        abandonAudioFocus()
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        mixer.stop()
        _playerState.value = _playerState.value.copy(isPlaying = false)
        releaseWakeLock()
        abandonAudioFocus()
    }

    fun togglePlayPause() {
        if (_playerState.value.isPlaying) pause() else play()
    }

    fun updateLoopMode(enabled: Boolean) {
        loopMode = enabled
        persistPrefs(sensorMapper?.soundDriveConfig ?: pendingSoundDriveConfig ?: SoundDriveConfig())
    }

    fun setManualVolumeDrums(v: Float) { soundDriveProcessor?.let { it.manualDrums = v.coerceIn(0f, 1f) }; persistVolumes() }
    fun setManualVolumeBass(v: Float) { soundDriveProcessor?.let { it.manualBass = v.coerceIn(0f, 1f) }; persistVolumes() }
    fun setManualVolumeOther(v: Float) { soundDriveProcessor?.let { it.manualOther = v.coerceIn(0f, 1f) }; persistVolumes() }
    fun setManualVolumeVocals(v: Float) { soundDriveProcessor?.let { it.manualVocals = v.coerceIn(0f, 1f) }; persistVolumes() }
    fun resetManualVolumes() {
        soundDriveProcessor?.let {
            it.manualDrums = 1f
            it.manualBass = 1f
            it.manualOther = 1f
            it.manualVocals = 1f
        }
        persistVolumes()
    }

    fun persistVolumes() {
        persistPrefs(sensorMapper?.soundDriveConfig ?: pendingSoundDriveConfig ?: SoundDriveConfig())
    }

    private fun handleTrackFinished() {
        if (loopMode) {
            playAt(currentIndex)
        } else if (hasNext()) {
            playNext()
        } else {
            stopInternal()
        }
    }

    fun playNext() {
        val nextIndex = if (loopMode && currentIndex in currentPlaylist.indices) currentIndex else currentIndex + 1
        if (nextIndex in currentPlaylist.indices) playAt(nextIndex)
    }

    fun playPrevious() {
        val prevIndex = if (loopMode && currentIndex in currentPlaylist.indices) currentIndex else currentIndex - 1
        if (prevIndex in currentPlaylist.indices) playAt(prevIndex)
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

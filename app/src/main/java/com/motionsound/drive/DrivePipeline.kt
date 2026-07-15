package com.motionsound.drive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.motionsound.data.DrivePreferences
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class DrivePipeline(private val context: Context) {

    private val sensorEngine = SensorEngine(context)
    private val attitudeEstimator = AttitudeEstimator()
    private val headingFusion = HeadingFusion()
    private val decomposer = MotionDecomposer()
    private val noiseFilter = NoiseFilter(VehiclePresetsProvider.lpfCutoffHz(VehiclePreset.CAR))
    private val classifier = DrivingClassifier()
    private val speedNormalizer = SpeedNormalizer()
    private val adaptiveEQ = AdaptiveEQ()
    private val smoother = ParameterSmoother(DrivingConfig.EQ_BAND_COUNT)

    @Volatile private var effectDepth = 0.7f
    @Volatile private var responseSpeed = 0.5f
    @Volatile private var accelSensitivity = 1.0f
    @Volatile private var cornerSensitivity = 1.0f
    @Volatile private var bumpFilterStrength = 0.5f
    @Volatile private var currentPreset = VehiclePreset.CAR
    @Volatile private var sensorSensitivity = 1.0f
    @Volatile private var pendingPresetTransition: VehiclePreset? = null
    private var pendingPresetFromBias: FloatArray = VehiclePresetsProvider.neutralEQBias(currentPreset)
    private var presetCrossfadeStartNs = 0L
    private val presetCrossfadeDurationNs = 1_500_000_000L
    private var smoothVolumeReductionDb = 0f
    private var syntheticSpeedMs = 0f
    private var idleMotionSmoothed = 0f
    private var tremoloBurstLevel = 0f
    private var smoothReverbWet = 0f

    private val _uiState = MutableStateFlow(DriveUiState())
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    private var pipelineJob: Job? = null
    private var lastTimestamp = 0L
    private var pipelineStartNanos = 0L
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        persistScope.launch { loadSavedPreferences() }
    }

    fun start() {
        if (pipelineJob?.isActive == true) return
        try {
            sensorEngine.start()
        } catch (e: Exception) {
            Log.e("DrivePipeline", "SensorEngine start failed", e)
        }
        smoother.reset()
        pipelineStartNanos = System.nanoTime()
        lastTimestamp = pipelineStartNanos

        pipelineJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                val frame = sensorEngine.read()
                if (frame == null) {
                    delay(20)
                    continue
                }

                val now = frame.timestampNanos
                val dt = if (lastTimestamp > 0) (now - lastTimestamp) / 1_000_000_000f
                else 0.01f
                lastTimestamp = now
                val dtClamped = dt.coerceIn(0.001f, 0.1f)

                attitudeEstimator.update(frame.gyro, frame.accel, dtClamped)

                val linear = if (frame.linearAccel != null) {
                    frame.linearAccel
                } else {
                    attitudeEstimator.getLinearAccel(frame.accel)
                }
                val aWorld = attitudeEstimator.getWorldFrameCorrected(linear, headingFusion.getHeading())

                val gravity = attitudeEstimator.getGravity()
                val gyroWorld = attitudeEstimator.getWorldFrame(frame.gyro)
                val omegaZWorld = gyroWorld[2]

                headingFusion.update(omegaZWorld, dtClamped)
                if (frame.gpsSpeed > DrivingConfig.MIN_SPEED_FOR_COURSE_MPS) {
                    headingFusion.onGpsFix(frame.gpsSpeed, frame.gpsBearing, frame.gpsAccuracy)
                }

                val gyroZDegPerS = omegaZWorld * 57.2958f

                val horizMag = sqrt(aWorld[0] * aWorld[0] + aWorld[1] * aWorld[1])
                val isMoving = abs(frame.accel[0]) > 0.3f || abs(frame.accel[1]) > 0.3f || frame.gpsSpeed > 0.5f
                if (abs(gyroZDegPerS) < 5f && horizMag > 0.3f && isMoving) {
                    decomposer.feedCalibration(aWorld, headingFusion.getHeading())
                }

                val motion = decomposer.decompose(aWorld, headingFusion.getHeading())

                pendingPresetTransition?.let { newPreset ->
                    val elapsed = now - presetCrossfadeStartNs
                    val t = (elapsed.toFloat() / presetCrossfadeDurationNs).coerceIn(0f, 1f)
                    val fromCutoff = VehiclePresetsProvider.lpfCutoffHz(currentPreset)
                    val toCutoff = VehiclePresetsProvider.lpfCutoffHz(newPreset)
                    noiseFilter.setCutoff(fromCutoff + (toCutoff - fromCutoff) * t)
                    if (t >= 1f) {
                        currentPreset = newPreset
                        pendingPresetTransition = null
                    }
                }

                val filtered = noiseFilter.filter(
                    motion, frame.gpsSpeed, omegaZWorld, now
                )

                var effectiveSpeedMs = frame.gpsSpeed
                if (effectiveSpeedMs > 0.5f) {
                    syntheticSpeedMs = effectiveSpeedMs
                } else {
                    syntheticSpeedMs += filtered.aLongFilt * dtClamped
                    syntheticSpeedMs = syntheticSpeedMs.coerceAtLeast(0f)
                    if (abs(filtered.aLongFilt) < 0.3f && abs(filtered.aLatFilt) < 0.3f) {
                        syntheticSpeedMs *= 0.995f
                    }
                }
                if (effectiveSpeedMs < 0.5f) effectiveSpeedMs = syntheticSpeedMs

                val classifierOut = classifier.update(
                    filtered, effectiveSpeedMs, gyroZDegPerS, headingFusion.headingConfidence, sensorSensitivity
                )
                headingFusion.setCorneringState(classifierOut.state == DrivingState.CORNERING)

                val speedKmh = effectiveSpeedMs * 3.6f
                val speedNorm = speedNormalizer.normalize(effectiveSpeedMs)

                val speedRatio = (speedKmh / speedNormalizer.maxSpeedKmh).coerceIn(0f, 1f)

                // Continuous volume target — no state-dependent discrete jumps
                val motionIntensity = maxOf(
                    classifierOut.accelIntensity,
                    classifierOut.brakeIntensity,
                    classifierOut.cornerIntensity,
                    speedNorm * 0.3f
                )
                val idleMotionAlpha = 0.08f
                idleMotionSmoothed += idleMotionAlpha * (motionIntensity - idleMotionSmoothed)
                val idleBlend = exp(-idleMotionSmoothed * 8f)

                val brakeVol = -classifierOut.brakeIntensity * abs(DrivingConfig.BRAKE_VOLUME_REDUCTION_DB)
                val cornerVolFactor = classifierOut.cornerIntensity * (1f - speedRatio).coerceIn(0f, 1f)
                val cornerVol = -cornerVolFactor * abs(DrivingConfig.CORNER_VOLUME_REDUCTION_DB)
                val accelVol = classifierOut.accelIntensity * DrivingConfig.ACCEL_VOLUME_BOOST_DB

                val drivingVol = accelVol + cornerVol
                val targetVolumeDb = idleBlend * DrivingConfig.IDLE_VOLUME_REDUCTION_DB +
                    (1f - idleBlend) * drivingVol

                val volAlpha = if (targetVolumeDb < smoothVolumeReductionDb)
                    DrivingConfig.VOL_SMOOTH_ATTACK else DrivingConfig.VOL_SMOOTH_RELEASE
                smoothVolumeReductionDb += volAlpha * (targetVolumeDb - smoothVolumeReductionDb)

                val depthWeight = effectDepth

                // Lowpass depth: 1.0 at idle (350 Hz), constant during motion
                val lowpassDepth = (idleBlend * 0.35f + (1f - idleBlend) * 0.5f).coerceIn(0f, 1f)

                val reverbWet = (classifierOut.cornerIntensity * DrivingConfig.REVERB_CORNER_DEPTH +
                    classifierOut.brakeIntensity * DrivingConfig.REVERB_BRAKE_DEPTH).coerceIn(0f, 1f)
                val reverbSmoothAlpha = 0.3f
                smoothReverbWet += reverbSmoothAlpha * (reverbWet - smoothReverbWet)

                if (filtered.bumpFlag) {
                    tremoloBurstLevel = 1f
                } else {
                    tremoloBurstLevel *= DrivingConfig.TREMOLO_BURST_DECAY
                }
                val tremoloDepth = (speedNorm * DrivingConfig.TREMOLO_SPEED_DEPTH +
                    tremoloBurstLevel * DrivingConfig.TREMOLO_BUMP_DEPTH).coerceIn(0f, 1f)

                val neutralBias = if (pendingPresetTransition != null) {
                    val t = ((now - presetCrossfadeStartNs).toFloat() / presetCrossfadeDurationNs).coerceIn(0f, 1f)
                    val from = pendingPresetFromBias
                    val to = VehiclePresetsProvider.neutralEQBias(pendingPresetTransition!!)
                    FloatArray(from.size) { i -> from[i] + (to[i] - from[i]) * t }
                } else {
                    VehiclePresetsProvider.neutralEQBias(currentPreset)
                }

                // Idle-to-driving EQ transition: bass/high cut at idle, restore on motion
                val biasWithIdle = FloatArray(neutralBias.size) { idx ->
                    neutralBias.getOrElse(idx) { 0f } + when (idx) {
                        0 -> DrivingConfig.IDLE_BASS_CUT_DB * idleBlend
                        4 -> DrivingConfig.IDLE_HIGH_CUT_DB * idleBlend
                        else -> 0f
                    }
                }

                val target = adaptiveEQ.computeTarget(
                    accelIntensity = classifierOut.accelIntensity,
                    brakeIntensity = classifierOut.brakeIntensity,
                    cornerIntensity = classifierOut.cornerIntensity,
                    speedNorm = speedNorm,
                    depthWeight = depthWeight,
                    accelSensitivity = accelSensitivity,
                    cornerSensitivity = cornerSensitivity,
                    neutralBias = biasWithIdle,
                    volumeReductionDb = smoothVolumeReductionDb
                )

                val attackMs = DrivingConfig.ATTACK_TIME_MS / (responseSpeed.coerceAtLeast(0.1f))
                val releaseMs = DrivingConfig.RELEASE_TIME_MS / (responseSpeed.coerceAtLeast(0.1f))
                smoother.update(target.bandGains, attackMs, releaseMs, dtClamped)
                val smoothedGains = smoother.getCurrent()
                val safeGains = smoothedGains.copyOf()
                for (i in safeGains.indices) {
                    if (!safeGains[i].isFinite()) safeGains[i] = 0f
                }
                val safeVolDb = if (smoothVolumeReductionDb.isFinite()) smoothVolumeReductionDb else 0f

                EqStateStore.state = EqState(
                    bandGains = safeGains,
                    volumeReductionDb = safeVolDb,
                    lowpassDepth = lowpassDepth,
                    reverbWet = smoothReverbWet,
                    tremoloDepth = tremoloDepth
                )

                _uiState.value = DriveUiState(
                    speed = effectiveSpeedMs,
                    speedKmh = speedKmh,
                    speedNorm = speedNorm,
                    accelIntensity = classifierOut.accelIntensity,
                    brakeIntensity = classifierOut.brakeIntensity,
                    cornerIntensity = classifierOut.cornerIntensity,
                    drivingState = classifierOut.state,
                    confidence = classifierOut.confidence,
                    eqBandGains = safeGains.toList(),
                    isServiceRunning = true,
                    accelSensitivity = accelSensitivity,
                    cornerSensitivity = cornerSensitivity,
                    effectDepth = effectDepth,
                    responseSpeed = responseSpeed,
                    bumpFilterStrength = bumpFilterStrength,
                    vehiclePreset = currentPreset,
                    maxSpeedKmh = speedNormalizer.maxSpeedKmh,
                    volumeReductionDb = safeVolDb,
                    sensorSensitivity = sensorSensitivity
                )

                val sleepMs = (1000L / DrivingConfig.SENSOR_RATE_HZ).coerceAtLeast(5L)
                delay(sleepMs)
                } catch (e: Exception) {
                    Log.e("DrivePipeline", "Pipeline iteration failed", e)
                    delay(10)
                }
            }
        }
    }

    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        sensorEngine.stop()
    }

    fun destroy() {
        stop()
        adaptiveEQ.release()
    }

    private suspend fun loadSavedPreferences() {
        try {
            effectDepth = DrivePreferences.getEffectDepth(context)
            responseSpeed = DrivePreferences.getResponseSpeed(context)
            accelSensitivity = DrivePreferences.getAccelSensitivity(context)
            cornerSensitivity = DrivePreferences.getCornerSensitivity(context)
            val bfs = DrivePreferences.getBumpFilterStrength(context)
            bumpFilterStrength = bfs
            val preset = DrivePreferences.getVehiclePreset(context)
            currentPreset = preset
            sensorSensitivity = DrivePreferences.getSensorSensitivity(context)
            speedNormalizer.maxSpeedKmh = DrivePreferences.getMaxSpeedKmh(context)
            noiseFilter.setCutoff(
                (VehiclePresetsProvider.lpfCutoffHz(preset) / bfs.coerceIn(0.1f, 2f))
                    .coerceIn(1f, 10f)
            )
        } catch (e: Exception) {
            Log.e("DrivePipeline", "Failed to load saved preferences", e)
        }
    }

    fun setEffectDepth(v: Float) {
        effectDepth = v.coerceIn(0f, 1f)
        persistScope.launch { DrivePreferences.setEffectDepth(context, effectDepth) }
    }

    fun setResponseSpeed(v: Float) {
        responseSpeed = v.coerceIn(0.1f, 1f)
        persistScope.launch { DrivePreferences.setResponseSpeed(context, responseSpeed) }
    }

    fun setAccelSensitivity(v: Float) {
        accelSensitivity = v.coerceIn(0.1f, 2f)
        persistScope.launch { DrivePreferences.setAccelSensitivity(context, accelSensitivity) }
    }

    fun setCornerSensitivity(v: Float) {
        cornerSensitivity = v.coerceIn(0.1f, 2f)
        persistScope.launch { DrivePreferences.setCornerSensitivity(context, cornerSensitivity) }
    }

    fun setBumpFilterStrength(v: Float) {
        bumpFilterStrength = v.coerceIn(0.1f, 2f)
        val cutoff = (VehiclePresetsProvider.lpfCutoffHz(currentPreset) / bumpFilterStrength)
            .coerceIn(1f, 10f)
        noiseFilter.setCutoff(cutoff)
        persistScope.launch { DrivePreferences.setBumpFilterStrength(context, bumpFilterStrength) }
    }

    fun setVehiclePreset(preset: VehiclePreset) {
        if (preset == currentPreset) return
        pendingPresetFromBias = VehiclePresetsProvider.neutralEQBias(currentPreset)
        pendingPresetTransition = preset
        presetCrossfadeStartNs = System.nanoTime()
        persistScope.launch { DrivePreferences.setVehiclePreset(context, preset) }
    }

    fun setMaxSpeed(kmh: Int) {
        speedNormalizer.maxSpeedKmh = kmh
        persistScope.launch { DrivePreferences.setMaxSpeedKmh(context, kmh) }
    }

    fun setSensorSensitivity(v: Float) {
        sensorSensitivity = v.coerceIn(0.25f, 4f)
        persistScope.launch { DrivePreferences.setSensorSensitivity(context, sensorSensitivity) }
    }
}

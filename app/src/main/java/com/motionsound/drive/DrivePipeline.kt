package com.motionsound.drive

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class DrivePipeline(private val context: Context) {

    private val sensorEngine = SensorEngine(context)
    private val attitudeEstimator = AttitudeEstimator()
    private val headingFusion = HeadingFusion()
    private val decomposer = MotionDecomposer()
    private val noiseFilter = NoiseFilter(VehiclePresetsProvider.lpfCutoffHz(VehiclePreset.CAR))
    private val classifier = DrivingClassifier()
    private val speedNormalizer = SpeedNormalizer()
    private var adaptiveEQ: AdaptiveEQ? = null
    private val reverb = AdaptiveReverb()
    private val smoother = ParameterSmoother(DrivingConfig.EQ_BAND_COUNT)

    private var effectDepth = 0.7f
    private var responseSpeed = 0.5f
    private var accelSensitivity = 1.0f
    private var cornerSensitivity = 1.0f
    private var bumpFilterStrength = 0.5f
    private var currentPreset = VehiclePreset.CAR

    private val _uiState = MutableStateFlow(DriveUiState())
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    private var pipelineJob: Job? = null
    private var lastTimestamp = 0L

    fun initEQ() {
        adaptiveEQ?.release()
        try {
            adaptiveEQ = AdaptiveEQ()
        } catch (_: Exception) {
            adaptiveEQ = null
        }
    }

    fun start() {
        if (pipelineJob?.isActive == true) return
        sensorEngine.start()
        smoother.reset()
        lastTimestamp = System.nanoTime()

        pipelineJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                val frame = sensorEngine.read()
                if (frame == null) {
                    _uiState.value = _uiState.value.copy(
                        accelSensitivity = accelSensitivity,
                        cornerSensitivity = cornerSensitivity,
                        effectDepth = effectDepth,
                        responseSpeed = responseSpeed,
                        bumpFilterStrength = bumpFilterStrength,
                        vehiclePreset = currentPreset,
                        maxSpeedKmh = speedNormalizer.maxSpeedKmh,
                        volumeReductionDb = _uiState.value.volumeReductionDb,
                        reverbIntensity = _uiState.value.reverbIntensity
                    )
                    delay(5)
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
                val aWorld = attitudeEstimator.getWorldFrame(linear)

                val gravity = attitudeEstimator.getGravity()
                val gyroWorld = attitudeEstimator.getWorldFrame(frame.gyro)
                val omegaZWorld = gyroWorld[2]

                headingFusion.update(omegaZWorld, dtClamped)
                if (frame.gpsSpeed > DrivingConfig.MIN_SPEED_FOR_COURSE_MPS) {
                    headingFusion.onGpsFix(frame.gpsSpeed, frame.gpsBearing, frame.gpsAccuracy)
                }

                val gyroZDegPerS = omegaZWorld * 57.2958f

                if (!decomposer.calibrated) {
                    val horizMag = sqrt(aWorld[0] * aWorld[0] + aWorld[1] * aWorld[1])
                    if (abs(gyroZDegPerS) < 5f && horizMag > 0.3f) {
                        decomposer.feedCalibration(aWorld, headingFusion.getHeading())
                    }
                }

                val motion = decomposer.decompose(aWorld, headingFusion.getHeading())

                val filtered = noiseFilter.filter(
                    motion, frame.gpsSpeed, omegaZWorld, now
                )

                val classifierOut = classifier.update(
                    filtered, frame.gpsSpeed, gyroZDegPerS, headingFusion.headingConfidence
                )
                headingFusion.setCorneringState(classifierOut.state == DrivingState.CORNERING)

                val speedKmh = frame.gpsSpeed * 3.6f
                val speedNorm = speedNormalizer.normalize(frame.gpsSpeed)

                val volumeReductionDb = when (classifierOut.state) {
                    DrivingState.IDLE -> DrivingConfig.IDLE_VOLUME_REDUCTION_DB
                    DrivingState.DECELERATING -> DrivingConfig.BRAKE_VOLUME_REDUCTION_DB
                    DrivingState.CORNERING -> {
                        if (speedKmh < DrivingConfig.CORNER_SPEED_THRESHOLD_KMH)
                            DrivingConfig.CORNER_VOLUME_REDUCTION_DB
                        else 0f
                    }
                    DrivingState.ACCELERATING -> DrivingConfig.ACCEL_VOLUME_BOOST_DB
                    else -> 0f
                }

                val depthWeight = effectDepth * (minOf(accelSensitivity, cornerSensitivity))
                val neutralBias = VehiclePresetsProvider.neutralEQBias(currentPreset)

                val reverbIntensity = when (classifierOut.state) {
                    DrivingState.IDLE -> DrivingConfig.REVERB_IDLE
                    DrivingState.SLOW_MANEUVERING -> DrivingConfig.REVERB_SLOW
                    DrivingState.ACCELERATING -> DrivingConfig.REVERB_ACCEL
                    DrivingState.CRUISING -> DrivingConfig.REVERB_CRUISE
                    DrivingState.DECELERATING -> DrivingConfig.REVERB_DECEL
                    DrivingState.CORNERING -> DrivingConfig.REVERB_CORNER
                }

                val eq = adaptiveEQ
                if (eq != null) {
                    val target = eq.computeTarget(
                        accelIntensity = classifierOut.accelIntensity,
                        brakeIntensity = classifierOut.brakeIntensity,
                        cornerIntensity = classifierOut.cornerIntensity,
                        speedNorm = speedNorm,
                        depthWeight = depthWeight,
                        neutralBias = neutralBias,
                        volumeReductionDb = volumeReductionDb
                    )

                    val attackMs = DrivingConfig.ATTACK_TIME_MS / (responseSpeed.coerceAtLeast(0.1f))
                    val releaseMs = DrivingConfig.RELEASE_TIME_MS / (responseSpeed.coerceAtLeast(0.1f))
                    smoother.update(target.bandGains, attackMs, releaseMs, dtClamped)

                    eq.applyTarget(EQTarget(smoother.getCurrent()))
                }

                reverb.applyIntensity(reverbIntensity, dtClamped)

                _uiState.value = DriveUiState(
                    speed = frame.gpsSpeed,
                    speedKmh = speedKmh,
                    speedNorm = speedNorm,
                    accelIntensity = classifierOut.accelIntensity,
                    brakeIntensity = classifierOut.brakeIntensity,
                    cornerIntensity = classifierOut.cornerIntensity,
                    drivingState = classifierOut.state,
                    confidence = classifierOut.confidence,
                    eqBandGains = smoother.getCurrent(),
                    isServiceRunning = true,
                    accelSensitivity = accelSensitivity,
                    cornerSensitivity = cornerSensitivity,
                    effectDepth = effectDepth,
                    responseSpeed = responseSpeed,
                    bumpFilterStrength = bumpFilterStrength,
                    vehiclePreset = currentPreset,
                    maxSpeedKmh = speedNormalizer.maxSpeedKmh,
                    volumeReductionDb = volumeReductionDb,
                    reverbIntensity = reverbIntensity
                )

                val sleepMs = (1000L / DrivingConfig.SENSOR_RATE_HZ).coerceAtLeast(5L)
                delay(sleepMs)
                } catch (_: Exception) {
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
        adaptiveEQ?.release()
        adaptiveEQ = null
        reverb.release()
    }

    fun setEffectDepth(v: Float) { effectDepth = v.coerceIn(0f, 1f) }
    fun setResponseSpeed(v: Float) { responseSpeed = v.coerceIn(0.1f, 1f) }
    fun setAccelSensitivity(v: Float) { accelSensitivity = v.coerceIn(0.1f, 2f) }
    fun setCornerSensitivity(v: Float) { cornerSensitivity = v.coerceIn(0.1f, 2f) }
    fun setBumpFilterStrength(v: Float) {
        bumpFilterStrength = v.coerceIn(0.1f, 2f)
        val cutoff = VehiclePresetsProvider.lpfCutoffHz(currentPreset) / bumpFilterStrength
        noiseFilter.setCutoff(cutoff.coerceIn(1f, 10f))
    }

    fun setVehiclePreset(preset: VehiclePreset) {
        currentPreset = preset
        val cutoff = VehiclePresetsProvider.lpfCutoffHz(preset)
        noiseFilter.setCutoff(cutoff)
    }

    fun setMaxSpeed(kmh: Int) { speedNormalizer.maxSpeedKmh = kmh }
}

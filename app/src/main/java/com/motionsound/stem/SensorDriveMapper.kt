package com.motionsound.stem

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.motionsound.drive.DrivingState
import com.motionsound.sounddrive.GestureType
import com.motionsound.sounddrive.SoundDriveConfig
import com.motionsound.sounddrive.SoundDriveProcessor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Calendar

private fun Float.sanitize(default: Float = 0f) = if (isNaN() || isInfinite()) default else this

class SensorDriveMapper(
    private val context: Context,
    private val mixer: StemMixer,
    private val onStateUpdate: (StemUiState) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val gameRotVec = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotVec = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravity = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    var soundDriveProcessor: SoundDriveProcessor? = null
    var soundDriveConfig: SoundDriveConfig = SoundDriveConfig()

    private val gameRotMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private var hasGameRotation = false
    private var hasRotation = false

    private var linAccelValid = false
    private var linAccelX = 0f
    private var linAccelY = 0f
    private var linAccelZ = 0f

    private val worldLinAccel = FloatArray(3)
    private var smoothLongAccel = 0f
    private var smoothLatAccel = 0f
    private var smoothYawRate = 0f
    private var lastAccelSmoothNs = 0L
    private var lastYawSmoothNs = 0L
    private var lastSdLogMs = 0L

    private var rawAccelX = 0f
    private var rawAccelY = 0f
    private var rawAccelZ = 9.81f
    private var rawGyroZ = 0f
    private var rawGyroX = 0f
    private var rawGyroY = 0f
    private var lastQuat = FloatArray(4)
    private var yawInt = 0f
    private var lastPcaRatio = 0f
    private var lastRawLogNs = 0L

    private val G = 9.81f

    @Volatile private var gpsSpeedMs = 0f
    @Volatile private var lastGoodSpeedMs = 0f
    @Volatile private var lastGpsSpeedTime = 0L
    @Volatile private var smoothGpsAccel = 0f
    @Volatile private var gpsPermissionDenied = false
    private var lastGpsFixMs = 0L
    private var gpsStaleLogged = false
    private var prevGpsSpeedMs = 0f
    private var prevGpsSpeedTimeMs = 0L
    private var lastGpsRetryMs = 0L
    private var longGHighSinceMs = 0L
    private val gpsWatchdogBootMs = System.currentTimeMillis()

    @Volatile private var lastGpsBearing = 0f
    @Volatile private var lastGpsLat = 0.0
    @Volatile private var lastGpsLon = 0.0
    @Volatile private var lastGpsAccuracy = 100f

    private fun currentGpsStatus(): GpsStatus =
        if (gpsPermissionDenied) GpsStatus.DENIED
        else if (lastGpsSpeedTime > 0L && System.currentTimeMillis() - lastGpsSpeedTime < 10_000L) GpsStatus.FIX
        else GpsStatus.WAITING

    private var lastState = StemUiState()
    private var lastGesture: GestureType? = null

    private var roadRoughness = 0f
    private var verticalJounce = 0f
    private var verticalLp = 0f
    private val verticalBuffer = FloatArray(20)
    private var verticalIdx = 0
    private var jounceDecay = 0f

    private var ambientMood = 0.5f

    private var lastPressure = 1013.25f
    private var lastPressureTime = 0L
    private var hillGrade = 0f

    private var cornerPrediction = 0f
    private var prevYawRate = 0f
    private var yawRateTrend = 0f

    private val pcaCov = FloatArray(9)
    private var pcaSamples = 0
    private var lastPcaComputeNs = 0L
    private var forwardLocked = false
    private val worldForward = FloatArray(3)
    private var flipCount = 0
    private var escapeStartNs = 0L
    private var escapeDone = false
    private var escapeArmed = false
    private var prevEscapeSign = 0
    private var reverseRunSec = 0f
    private var reverseTotalSec = 0f
    private var motionTotalSec = 0f
    private var gpsFlipVotes = 0
    private var gpsFlipVoteStartMs = 0L
    private var prevGpsBearingRad = Float.NaN
    private var lastLockedOffset = 0f
    private var hasLockedOffset = false
    private var calibAccX = 0f
    private var calibAccY = 0f
    private var calibTime = 0f
    private var cornerActive = false
    private var lastCornerEndNs = Long.MAX_VALUE
    private var recalCandidateX = 0f
    private var recalCandidateY = 0f
    private var recalVotes = 0
    private var recalPending = false

    private var gyroBias = 0f
    private var biasAccWz = 0f
    private var biasAccTime = 0f

    private var bearingOffset = 0f
    private var bearingOffsetValid = false
    private var bearingOffsetVotes = 0
    private var gpsBearingVotes = 0
    private var gpsBearingErr = 0f
    private var bearingOffsetFrameGame = true

    private var brakeType = BrakeType.FRICTION
    private var verticalSmoothnessLp = 0.5f

    fun start() {
        val sm = sensorManager ?: return
        AppLogger.event("SD_SESSION", "SESSION_START", "epoch=" + System.currentTimeMillis())
        gameRotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        AppLogger.event("SD_SESSION", "SESSION_END", "epoch=" + System.currentTimeMillis())
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = event.values
                lastQuat[0] = q[0]; lastQuat[1] = q[1]; lastQuat[2] = q[2]; lastQuat[3] = q[3]
                SensorManager.getRotationMatrixFromVector(gameRotMatrix, event.values)
                hasGameRotation = true
                if (linAccelValid) processWorldAccel()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!hasGameRotation) {
                    val q = event.values
                    lastQuat[0] = q[0]; lastQuat[1] = q[1]; lastQuat[2] = q[2]; lastQuat[3] = q[3]
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    hasRotation = true
                    if (linAccelValid) processWorldAccel()
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linAccelX = event.values[0]; linAccelY = event.values[1]; linAccelZ = event.values[2]
                linAccelValid = true
                if (hasGameRotation || hasRotation) processWorldAccel()
            }
            Sensor.TYPE_GRAVITY -> {}
            Sensor.TYPE_ACCELEROMETER -> {
                rawAccelX = event.values[0]; rawAccelY = event.values[1]; rawAccelZ = event.values[2]
                if (!linAccelValid && (hasGameRotation || hasRotation)) processRawAccel()
                if (!linAccelValid && !hasGameRotation && !hasRotation) processFallback()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val evZ = event.values[2]
                rawGyroX = event.values[0]
                rawGyroY = event.values[1]
                if (hasGameRotation || hasRotation) {
                    val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
                    val wz = mat[8] * event.values[0] + mat[9] * event.values[1] + mat[10] * evZ
                    val nowNs = System.nanoTime()
                    val dt = if (lastYawSmoothNs == 0L) 0.016f else ((nowNs - lastYawSmoothNs) / 1e9f).coerceIn(0.001f, 0.3f)
                    lastYawSmoothNs = nowNs
                    prevYawRate = smoothYawRate
                    if (forwardLocked && abs(smoothYawRate) < 0.05f) {
                        biasAccWz += wz * dt
                        biasAccTime += dt
                        if (biasAccTime >= 3f) {
                            val meanWz = biasAccWz / biasAccTime
                            gyroBias += 0.1f * (meanWz - gyroBias)
                            gyroBias = gyroBias.coerceIn(-0.3f, 0.3f)
                            biasAccWz = 0f
                            biasAccTime = 0f
                            AppLogger.throttled("SD_FWD", "bias", 10_000L, "bias=" + gyroBias)
                        }
                    } else {
                        biasAccWz = 0f
                        biasAccTime = 0f
                    }
                    val wzComp = wz - gyroBias
                    val profile = soundDriveConfig.effectiveSensorProfile
                    val tau = (profile.inputTauMs / 1000f).coerceAtLeast(0.02f)
                    val k = 1f - exp(-dt / tau)
                    smoothYawRate += k * (wzComp - smoothYawRate)
                    yawRateTrend = smoothYawRate - prevYawRate
                    if (forwardLocked) {
                        rotateForward(wzComp * dt)
                        yawInt += wzComp * dt
                    }
                } else {
                    rawGyroZ += 0.15f * (evZ - rawGyroZ)
                }
            }
            Sensor.TYPE_PRESSURE -> {
                val now = System.nanoTime()
                if (lastPressureTime > 0) {
                    val dt = (now - lastPressureTime) / 1e9f
                    if (dt > 0.3f) {
                        val rate = (event.values[0] - lastPressure) / dt
                        hillGrade = (-rate / 0.12f).coerceIn(-1f, 1f)
                    }
                }
                lastPressure = event.values[0]
                lastPressureTime = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun processWorldAccel() {
        val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
        worldLinAccel[0] = mat[0] * linAccelX + mat[1] * linAccelY + mat[2] * linAccelZ
        worldLinAccel[1] = mat[4] * linAccelX + mat[5] * linAccelY + mat[6] * linAccelZ
        worldLinAccel[2] = mat[8] * linAccelX + mat[9] * linAccelY + mat[10] * linAccelZ
        computeDynamics(worldLinAccel[0], worldLinAccel[1], worldLinAccel[2])
    }

    private fun processRawAccel() {
        val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
        val rx = rawAccelX; val ry = rawAccelY; val rz = rawAccelZ
        worldLinAccel[0] = mat[0] * rx + mat[1] * ry + mat[2] * rz
        worldLinAccel[1] = mat[4] * rx + mat[5] * ry + mat[6] * rz
        worldLinAccel[2] = mat[8] * rx + mat[9] * ry + mat[10] * rz + G
        computeDynamics(worldLinAccel[0], worldLinAccel[1], worldLinAccel[2])
    }

    private fun computeDynamics(wx: Float, wy: Float, wz: Float) {
        var longAccel: Float
        var latAccel: Float

        val profile = soundDriveConfig.effectiveSensorProfile
        val tau = (profile.inputTauMs / 1000f).coerceAtLeast(0.02f)
        val nowNs = System.nanoTime()
        val dt = if (lastAccelSmoothNs == 0L) 0.016f else ((nowNs - lastAccelSmoothNs) / 1e9f).coerceIn(0.001f, 0.3f)
        lastAccelSmoothNs = nowNs

        if (forwardLocked) {
            val f = worldForward
            longAccel = wx * f[0] + wy * f[1] + wz * f[2]
            latAccel = wx * f[1] - wy * f[0]
        } else {
            longAccel = wy
            latAccel = wx
        }

        longAccel = longAccel.sanitize()
        latAccel = latAccel.sanitize()

        val magA = sqrt(wx * wx + wy * wy + wz * wz)
        if (forwardLocked && magA > 0.1f * G) {
            val sign = if (longAccel > 0.12f * G) 1 else if (longAccel < -0.12f * G) -1 else prevEscapeSign
            if (!escapeArmed && prevEscapeSign != 0 && sign != prevEscapeSign) {
                escapeArmed = true
                motionTotalSec = 0f
                reverseTotalSec = 0f
                reverseRunSec = 0f
            }
            prevEscapeSign = sign
            if (escapeArmed && !escapeDone) {
                if (nowNs - escapeStartNs < 60_000_000_000L) {
                    motionTotalSec += dt
                    if (sign < 0) {
                        reverseRunSec += dt
                        reverseTotalSec += dt
                    } else {
                        reverseRunSec = 0f
                    }
                    if (motionTotalSec > 5f && reverseRunSec >= 5f && reverseTotalSec > 0.6f * motionTotalSec) {
                        escapeDone = true
                        flipForward("reverse-escape")
                    }
                } else {
                    escapeDone = true
                }
            }
        }

        val gpsOnly = soundDriveConfig.gpsMode

        val k = 1f - exp(-dt / tau)

        smoothLongAccel += k * (longAccel - smoothLongAccel).sanitize()
        smoothLatAccel += k * (latAccel - smoothLatAccel).sanitize()

        val longSigned = smoothLongAccel.sanitize()
        val longG = abs(longSigned) / G
        val latG = abs(smoothLatAccel.sanitize()) / G
        if (lastGpsSpeedTime > 0L) {
            val gpsStaleMs = System.currentTimeMillis() - lastGpsSpeedTime
            if (gpsStaleMs > 3000L) {
                val staleSec = (gpsStaleMs / 1000f).coerceAtLeast(0f)
                gpsSpeedMs = lastGoodSpeedMs * exp(-0.03f * staleSec)
            }
        }
        val speedKmh = (gpsSpeedMs * 3.6f).sanitize()
        val capKmh = soundDriveConfig.speedCapKmh.takeIf { it > 0f }?.coerceAtLeast(1f)
        val speedGate = ((speedKmh / 58f).coerceIn(0f, 1f)).let { raw ->
            val capped = if (capKmh != null) minOf(raw, (capKmh / 58f).coerceIn(0f, 1f)) else raw
            capped.sanitize()
        }

        val gpsFreshAgeMs = if (lastGpsSpeedTime > 0L) System.currentTimeMillis() - lastGpsSpeedTime else Long.MAX_VALUE
        if (longG > 0.2f) {
            if (longGHighSinceMs == 0L) longGHighSinceMs = System.currentTimeMillis()
        } else {
            longGHighSinceMs = 0L
        }
        val moving = (gpsFreshAgeMs < 15_000L && (gpsSpeedMs > 1.4f || lastGoodSpeedMs > 1.4f)) ||
            (gpsFreshAgeMs >= 15_000L && longGHighSinceMs != 0L && System.currentTimeMillis() - longGHighSinceMs >= 1500L)

        if (moving) updatePcaAxis(wx, wy, wz, dt, nowNs)

        if (forwardLocked) updateForwardCalibration(wx, wy, dt, speedGate, longG, abs(smoothLatAccel.sanitize()) / G, moving)

        if (!gpsPermissionDenied) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - gpsWatchdogBootMs > 45_000L) {
                val sinceLastFixMs = if (lastGpsSpeedTime > 0L) nowMs - lastGpsSpeedTime else Long.MAX_VALUE
                if (sinceLastFixMs > 45_000L && nowMs - lastGpsRetryMs > 15_000L) {
                    lastGpsRetryMs = nowMs
                    AppLogger.w("SD_GPS", "NO_FIX_45s re-registering listeners")
                    enableGpsSpeed()
                }
            }
        }

        val accelG = if (longSigned > 0f) longSigned / G else 0f
        val rawAccelIntensity = (accelG * profile.accelSensitivity).coerceIn(0f, 1f).sanitize()
        val accelIntensity = if (rawAccelIntensity < 0.06f) 0f else rawAccelIntensity
        val cornerLat = ((latG / 0.5f) * profile.cornerSensitivity).coerceIn(0f, 1f).sanitize()

        val braking = longSigned < -0.4f
        val brakeIntensity = if (braking) ((-longSigned / (1.5f * G)).coerceIn(0f, 1f) * profile.accelSensitivity).sanitize() else 0f

        computeRoadRoughness(wz.sanitize(), profile.bumpFiltering)
        computeCornerPrediction(profile.cornerPredictionS)
        updateAmbientMood()
        updateBrakeType()

        roadRoughness = roadRoughness.sanitize()
        verticalJounce = verticalJounce.sanitize()
        hillGrade = hillGrade.sanitize()
        ambientMood = ambientMood.sanitize(0.5f)
        if (!moving) {
            roadRoughness = 0f
            verticalJounce = 0f
        }

        val cornerTotal = if (moving) maxOf(cornerLat, cornerPrediction).coerceIn(0f, 1f).sanitize() else 0f

        val gpsAccelSmooth = if (gpsOnly) smoothGpsAccel.sanitize() else 0f
        val effectiveAccel = if (gpsOnly) gpsAccelSmooth.coerceIn(0f, 1f) else accelIntensity
        val gpsBrake = if (smoothGpsAccel < 0f) (-smoothGpsAccel / 0.3f).coerceIn(0f, 1f) else 0f
        val effectiveBrake = if (gpsOnly) maxOf(brakeIntensity, gpsBrake) else brakeIntensity

        val drivingState = when {
            cornerTotal > 0.4f && smoothYawRate > 0.25f -> DrivingState.CORNERING
            effectiveAccel > 0.3f -> DrivingState.ACCELERATING
            effectiveBrake > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        val processor = soundDriveProcessor
        if (processor != null) {
            val signedCornerPan = if (smoothYawRate != 0f) {
                smoothYawRate.coerceIn(-1f, 1f) * (cornerTotal.coerceAtLeast(0.05f))
            } else {
                cornerTotal * if (latAccel > 0f) -1f else 1f
            }
            lastGesture = processor.update(
                effectiveAccel, effectiveBrake, cornerTotal, speedGate,
                drivingState, soundDriveConfig,
                roadRoughness, ambientMood, hillGrade, brakeType, verticalJounce,
                signedCornerPan
            )
            val sdNowMs = System.currentTimeMillis()
            if (sdNowMs - lastSdLogMs > 1000L) {
                lastSdLogMs = sdNowMs
                AppLogger.i(
                    "SD_LAYER",
                    "speed=" + speedKmh + " gate=" + speedGate + " accel=" + effectiveAccel +
                        " brake=" + effectiveBrake + " corner=" + cornerTotal +
                        " state=" + drivingState + " gpsOnly=" + gpsOnly +
                        " yaw=" + smoothYawRate + " fwdLocked=" + forwardLocked +
                        " gpsAccel=" + smoothGpsAccel + " rough=" + roadRoughness +
                        " jounce=" + verticalJounce + " hill=" + hillGrade +
                        " brakeType=" + brakeType + " mood=" + ambientMood +
                        " vocGate=" + mixer.vocalsGateActive +
                        " vocT=" + mixer.vocalsGateTarget + " vocV=" + mixer.volumeVocals
                )
            }
        } else {
            mixer.volumeDrums = lerp(0f, 1f, maxOf(effectiveAccel, cornerTotal * 0.8f))
            mixer.volumeBass = lerp(0f, 1f, maxOf(effectiveAccel * 0.7f, speedGate * 0.9f))
            mixer.volumeOther = lerp(0.8f, 1f, cornerTotal * 0.5f)
            mixer.volumeVocals = lerp(0f, 1f, speedGate * 0.6f + effectiveAccel * 0.2f)
            lastGesture = null
        }

        val rawNowNs = System.nanoTime()
        if (rawNowNs - lastRawLogNs >= 100_000_000L) {
            lastRawLogNs = rawNowNs
            val haveRot = hasGameRotation || hasRotation
            val qx = lastQuat[0]; val qy = lastQuat[1]; val qz = lastQuat[2]; val qw = lastQuat[3]
            val roll = atan2(2f * (qw * qx + qy * qz), 1f - 2f * (qx * qx + qy * qy))
            val pitch = asin((2f * (qw * qy - qz * qx)).coerceIn(-1f, 1f))
            val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
            val heading = atan2(mat[3], mat[0])
            val fwdRad = atan2(worldForward[0], worldForward[1])
            AppLogger.i(
                "SD_RAW",
                "t=" + System.currentTimeMillis() +
                    " a=" + rawAccelX + "," + rawAccelY + "," + rawAccelZ +
                    " g=" + rawGyroX + "," + rawGyroY + "," + rawGyroZ +
                    " rp=" + "%.2f".format(roll * 180f / PI.toFloat()) + "," + "%.2f".format(pitch * 180f / PI.toFloat()) +
                    " h=" + "%.2f".format(heading * 180f / PI.toFloat()) +
                    " fwd=" + "%.4f".format(worldForward[0]) + "," + "%.4f".format(worldForward[1]) +
                    " fh=" + "%.2f".format(fwdRad * 180f / PI.toFloat()) +
                    " rot=" + (if (haveRot) 1 else 0) +
                    " yaw=" + "%.3f".format(smoothYawRate) +
                    " yawInt=" + "%.3f".format(yawInt) +
                    " long=" + "%.3f".format(smoothLongAccel) +
                    " lat=" + "%.3f".format(smoothLatAccel) +
                    " latG=" + "%.3f".format(latG) +
                    " pca=" + (if (forwardLocked) 1 else 0) + "," + pcaSamples + "," + "%.2f".format(lastPcaRatio) +
                    " cal=" + (if (cornerActive) 1 else 0) + "," + recalVotes + "," + "%.2f".format(calibTime) + "," + (if (recalPending) 1 else 0) +
                    " esc=" + prevEscapeSign + "," + (if (escapeArmed) 1 else 0) + "," + "%.2f".format(reverseRunSec) +
                    " gps=" + "%.2f".format(gpsSpeedMs) + "," + "%.1f".format(lastGpsBearing) + "," + "%.1f".format(lastGpsAccuracy) +
                    " pred=" + "%.3f".format(cornerPrediction) +
                    " state=" + drivingState +
                    " press=" + "%.1f".format(lastPressure) +
                    " hill=" + "%.3f".format(hillGrade)
            )
        }

        lastState = lastState.copy(
            speed = gpsSpeedMs, speedKmh = speedKmh,
            accelIntensity = effectiveAccel, brakeIntensity = effectiveBrake,
            cornerIntensity = cornerTotal,
            drivingState = drivingState,
            volumeDrums = mixer.volumeDrums, volumeBass = mixer.volumeBass,
            volumeOther = mixer.volumeOther, volumeVocals = mixer.volumeVocals,
            soundDriveEnabled = soundDriveConfig.enabled,
            soundDriveMode = soundDriveConfig.mode,
            gestureIndicator = lastGesture,
            roadRoughness = roadRoughness, ambientMood = ambientMood,
            hillGrade = hillGrade, brakeType = brakeType,
            gpsMode = soundDriveConfig.gpsMode,
            gpsStatus = currentGpsStatus()
        )
        onStateUpdate(lastState)
    }

    private fun updatePcaAxis(wx: Float, wy: Float, wz: Float, dt: Float, nowNs: Long) {
        val mag = sqrt(wx * wx + wy * wy + wz * wz)
        val hmag = sqrt(wx * wx + wy * wy)
        if (mag > 0.1f * G && hmag > 0.05f * G && abs(smoothYawRate) < 0.15f) {
            val k = (dt / 5f).coerceIn(0f, 0.2f)
            val ax = wx / hmag
            val ay = wy / hmag
            val az = 0f
            pcaCov[0] += k * (ax * ax - pcaCov[0])
            pcaCov[1] += k * (ax * ay - pcaCov[1])
            pcaCov[2] += k * (ax * az - pcaCov[2])
            pcaCov[4] += k * (ay * ay - pcaCov[4])
            pcaCov[5] += k * (ay * az - pcaCov[5])
            pcaCov[8] += k * (az * az - pcaCov[8])
            pcaCov[3] = pcaCov[1]
            pcaCov[6] = pcaCov[2]
            pcaCov[7] = pcaCov[5]
            pcaSamples++
            if (pcaSamples >= 25 && nowNs - lastPcaComputeNs >= 1_000_000_000L) {
                lastPcaComputeNs = nowNs
                val e = dominantEigenvector(pcaCov)
                val ratio = e[3]
                lastPcaRatio = ratio
                if (ratio >= 3f) {
                    if (forwardLocked) {
                        val dot = e[0] * worldForward[0] + e[1] * worldForward[1] + e[2] * worldForward[2]
                        if (dot > 0.5f) {
                            worldForward[0] += (e[0] - worldForward[0]) * 0.1f
                            worldForward[1] += (e[1] - worldForward[1]) * 0.1f
                            worldForward[2] += (e[2] - worldForward[2]) * 0.1f
                            val wm = sqrt(worldForward[0] * worldForward[0] + worldForward[1] * worldForward[1] + worldForward[2] * worldForward[2])
                            if (wm > 1e-6f) {
                                worldForward[0] /= wm
                                worldForward[1] /= wm
                                worldForward[2] = 0f
                            }
                        }
                    } else {
                        worldForward[0] = e[0]
                        worldForward[1] = e[1]
                        worldForward[2] = 0f
                        forwardLocked = true
                        escapeStartNs = nowNs
                        invalidateBearingOffset()
                        AppLogger.i(
                            "SD_FWD",
                            "axis locked (PCA) fwd=(" + e[0] + "," + e[1] + "," + e[2] + ") ratio=" + ratio
                        )
                    }
                }
            }
        }
    }

    private fun dominantEigenvector(c: FloatArray): FloatArray {
        val a = c.copyOf()
        val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        repeat(10) {
            jacobiSweep(a, v, 0, 1)
            jacobiSweep(a, v, 0, 2)
            jacobiSweep(a, v, 1, 2)
        }
        var best = 0
        for (i in 1..2) if (a[i * 3 + i] > a[best * 3 + best]) best = i
        val evs = floatArrayOf(a[0], a[4], a[8]).sortedDescending()
        val ratio = if (evs[1] > 1e-9f) evs[0] / evs[1] else 999f
        val e0 = v[best * 3]
        val e1 = v[best * 3 + 1]
        val e2 = v[best * 3 + 2]
        val n = sqrt(e0 * e0 + e1 * e1 + e2 * e2)
        if (n < 1e-6f) return floatArrayOf(1f, 0f, 0f, ratio)
        return floatArrayOf(e0 / n, e1 / n, e2 / n, ratio)
    }

    private fun jacobiSweep(a: FloatArray, v: FloatArray, p: Int, q: Int) {
        if (abs(a[q * 3 + p]) < 1e-12f) return
        val theta = (a[q * 3 + q] - a[p * 3 + p]) / (2f * a[q * 3 + p])
        val t = if (theta >= 0f) 1f / (theta + sqrt(theta * theta + 1f)) else 1f / (theta - sqrt(theta * theta + 1f))
        val c = 1f / sqrt(t * t + 1f)
        val s = t * c
        val app = a[p * 3 + p]
        val aqq = a[q * 3 + q]
        val apq = a[q * 3 + p]
        a[p * 3 + p] = c * c * app - 2f * s * c * apq + s * s * aqq
        a[q * 3 + q] = s * s * app + 2f * s * c * apq + c * c * aqq
        a[q * 3 + p] = 0f
        a[p * 3 + q] = 0f
        for (i in 0..2) {
            if (i != p && i != q) {
                val aip = a[i * 3 + p]
                val aiq = a[i * 3 + q]
                a[i * 3 + p] = c * aip - s * aiq
                a[p * 3 + i] = a[i * 3 + p]
                a[i * 3 + q] = s * aip + c * aiq
                a[q * 3 + i] = a[i * 3 + q]
            }
            val vip = v[i * 3 + p]
            val viq = v[i * 3 + q]
            v[i * 3 + p] = c * vip - s * viq
            v[i * 3 + q] = s * vip + c * viq
        }
    }

    private fun flipForward(reason: String) {
        if (!forwardLocked || flipCount >= 2) return
        worldForward[0] = -worldForward[0]
        worldForward[1] = -worldForward[1]
        worldForward[2] = -worldForward[2]
        flipCount++
        cornerActive = false
        recalPending = false
        calibAccX = 0f
        calibAccY = 0f
        calibTime = 0f
        escapeArmed = false
        prevEscapeSign = 0
        reverseRunSec = 0f
        reverseTotalSec = 0f
        motionTotalSec = 0f
        yawInt = 0f
        invalidateBearingOffset()
        AppLogger.w(
            "SD_FWD",
            "flipped by " + reason + " (flip=" + flipCount + ") fwd=(" + worldForward[0] + "," + worldForward[1] + "," + worldForward[2] + ")"
        )
    }

    private fun rotateForward(theta: Float) {
        if (abs(theta) < 1e-5f) return
        val c = cos(theta)
        val s = sin(theta)
        val fx = worldForward[0]
        val fy = worldForward[1]
        worldForward[0] = fx * c - fy * s
        worldForward[1] = fx * s + fy * c
    }

    private fun invalidateBearingOffset() {
        bearingOffsetValid = false
        bearingOffsetVotes = 0
        gpsBearingVotes = 0
        gpsBearingErr = 0f
    }

    private fun wrapAngle(a: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var r = a % twoPi
        if (r > PI.toFloat()) r -= twoPi
        if (r < -PI.toFloat()) r += twoPi
        return r
    }

    private fun updateForwardCalibration(wx: Float, wy: Float, dt: Float, speedGate: Float, longG: Float, latG: Float, moving: Boolean) {
        if (!moving) {
            calibAccX = 0f
            calibAccY = 0f
            calibTime = 0f
            return
        }
        val cornerNow = cornerActive || abs(smoothYawRate) > 0.15f || latG > 0.1f
        if (cornerNow) {
            if (!cornerActive) {
                cornerActive = true
                recalVotes = 0
                recalPending = false
            }
            calibAccX = 0f
            calibAccY = 0f
            calibTime = 0f
            return
        }
        if (cornerActive) {
            cornerActive = false
            lastCornerEndNs = System.nanoTime()
            recalVotes = 0
            recalPending = false
        }
        if (speedGate <= 0.05f && longG <= 0.1f) {
            calibAccX = 0f
            calibAccY = 0f
            calibTime = 0f
            return
        }
        calibAccX += wx
        calibAccY += wy
        calibTime += dt
        if (calibTime < 2f) return
        val mag = sqrt(calibAccX * calibAccX + calibAccY * calibAccY)
        if (mag > 0.25f) {
            var targetX = calibAccX / mag
            var targetY = calibAccY / mag
            if (worldForward[0] * targetX + worldForward[1] * targetY < 0f) {
                targetX = -targetX
                targetY = -targetY
            }
            val postCorner = System.nanoTime() - lastCornerEndNs < 8_000_000_000L
            if (postCorner) {
                if (!recalPending || recalCandidateX * targetX + recalCandidateY * targetY < 0.92f) {
                    recalCandidateX = targetX
                    recalCandidateY = targetY
                    recalVotes = 1
                    recalPending = true
                } else {
                    recalVotes++
                }
                if (lastPcaRatio >= 2.2f && (recalVotes >= 2 || (recalVotes >= 1 && mag > 0.3f))) {
                    worldForward[0] = targetX
                    worldForward[1] = targetY
                    worldForward[2] = 0f
                    invalidateBearingOffset()
                    AppLogger.i(
                        "SD_FWD",
                        "re-locked after corner fwd=(" + worldForward[0] + "," + worldForward[1] + ")"
                    )
                    recalVotes = 0
                    recalPending = false
                }
            } else {
                val cross = worldForward[0] * targetY - worldForward[1] * targetX
                val dot = worldForward[0] * targetX + worldForward[1] * targetY
                val dTheta = (atan2(cross, dot) * 0.3f).coerceIn(-0.08f, 0.08f)
                rotateForward(dTheta)
                if (abs(dTheta) > 0.005f) {
                    AppLogger.i(
                        "SD_FWD",
                        "calib dTheta=" + dTheta + " fwd=(" + worldForward[0] + "," + worldForward[1] + ")"
                    )
                }
            }
        }
        calibAccX = 0f
        calibAccY = 0f
        calibTime = 0f
    }

    private fun processFallback() {
        val gravityNorm = rawAccelZ.coerceAtLeast(1f)
        val lateralG = abs(rawAccelX) / gravityNorm
        val longSigned = rawAccelY
        val longG = abs(longSigned) / gravityNorm
        val speedKmh = gpsSpeedMs * 3.6f
        val speedGate = (speedKmh / 58f).coerceIn(0f, 1f)

        val accelG = if (longSigned > 0f) longSigned / gravityNorm else 0f
        val accelIntensity = accelG.coerceIn(0f, 1f)
        val cornerIntensity = if (gpsSpeedMs < 1.4f && lastGoodSpeedMs < 1.4f) 0f else (lateralG / 0.5f).coerceIn(0f, 1f)

        val braking = longSigned < -0.4f
        val brakeIntensity = if (braking) (-longSigned / (1.5f * gravityNorm)).coerceIn(0f, 1f) else 0f

        val drivingState = when {
            cornerIntensity > 0.4f && abs(rawGyroZ) > 0.5f -> DrivingState.CORNERING
            accelIntensity > 0.3f -> DrivingState.ACCELERATING
            brakeIntensity > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        val processor = soundDriveProcessor
        if (processor != null) {
            val signedCornerPan = if (abs(rawGyroZ) > 0.2f) {
                (rawGyroZ / 3f).coerceIn(-1f, 1f) * cornerIntensity
            } else {
                cornerIntensity * if (rawAccelX > 0f) 1f else -1f
            }
            lastGesture = processor.update(
                accelIntensity, brakeIntensity, cornerIntensity, speedGate,
                drivingState, soundDriveConfig,
                0f, 0.5f, 0f, BrakeType.FRICTION, 0f,
                signedCornerPan
            )
        } else {
            mixer.volumeDrums = lerp(0f, 1f, maxOf(accelIntensity, cornerIntensity * 0.8f))
            mixer.volumeBass = lerp(0f, 1f, maxOf(accelIntensity * 0.7f, speedGate * 0.9f))
            mixer.volumeOther = lerp(0.8f, 1f, cornerIntensity * 0.5f)
            mixer.volumeVocals = lerp(0f, 1f, speedGate * 0.6f + accelIntensity * 0.2f)
            lastGesture = null
        }

        lastState = lastState.copy(
            speed = gpsSpeedMs, speedKmh = speedKmh,
            accelIntensity = accelIntensity, brakeIntensity = brakeIntensity,
            cornerIntensity = cornerIntensity,
            drivingState = drivingState,
            volumeDrums = mixer.volumeDrums, volumeBass = mixer.volumeBass,
            volumeOther = mixer.volumeOther, volumeVocals = mixer.volumeVocals,
            soundDriveEnabled = soundDriveConfig.enabled,
            soundDriveMode = soundDriveConfig.mode,
            gestureIndicator = lastGesture,
            roadRoughness = 0f, ambientMood = 0.5f,
            hillGrade = 0f, brakeType = BrakeType.FRICTION,
            gpsMode = soundDriveConfig.gpsMode,
            gpsStatus = currentGpsStatus()
        )
        onStateUpdate(lastState)
    }

    private fun computeRoadRoughness(vertical: Float, bumpFiltering: Float) {
        verticalLp += 0.995f * (vertical - verticalLp)
        val hp = vertical - verticalLp
        verticalBuffer[verticalIdx % 20] = hp * hp
        verticalIdx++
        val sum = verticalBuffer.sum()
        val rms = sqrt(sum / 20f)
        roadRoughness = (rms / (0.5f + bumpFiltering * 2f)).coerceIn(0f, 1f)

        val absHp = abs(hp)
        if (absHp > jounceDecay) {
            jounceDecay = absHp
        } else {
            jounceDecay *= 0.85f
        }
        verticalJounce = (jounceDecay / (0.5f + bumpFiltering * 3f)).coerceIn(0f, 1f)
    }

    private fun computeCornerPrediction(predictionGain: Float) {
        val absYaw = abs(smoothYawRate)
        val rawPrediction = (absYaw / 50f).coerceIn(0f, 1f)
        cornerPrediction = rawPrediction * predictionGain * 2f
    }

    private fun updateAmbientMood() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        ambientMood = (0.35f + 0.35f * cos(2.0 * Math.PI * (hour - 14) / 24.0)).toFloat()
    }

    private fun updateBrakeType() {
        if (smoothLongAccel < -0.5f) {
            val hp = worldLinAccel[2] - verticalLp
            verticalSmoothnessLp += 0.9f * (abs(hp) - verticalSmoothnessLp)
            brakeType = if (verticalSmoothnessLp < 0.3f) BrakeType.REGEN else BrakeType.FRICTION
        } else {
            brakeType = BrakeType.FRICTION
        }
    }

    fun enableGpsSpeed() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            gpsPermissionDenied = true
            AppLogger.w("SD_GPS", "PERMISSION_DENIED fine=$hasFine coarse=$hasCoarse (retry on next service start)")
            return
        }
        gpsPermissionDenied = false
        val bgLocationGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        AppLogger.i("SD_GPS", "ENABLE fine=$hasFine coarse=$hasCoarse bg=$bgLocationGranted")
        val providersEnabled = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).map { p -> p + "=" + lm.isProviderEnabled(p) }.joinToString(" ")
        AppLogger.i("SD_GPS", "PROVIDERS " + providersEnabled)
        try {
            val seedLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            if (seedLoc != null && seedLoc.hasSpeed()) {
                gpsSpeedMs = seedLoc.speed
                lastGoodSpeedMs = seedLoc.speed
                lastGpsSpeedTime = System.currentTimeMillis()
            }
        } catch (e: SecurityException) {
            gpsPermissionDenied = true
            AppLogger.w("SD_GPS", "SEED_SECURITY_EXCEPTION")
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val nowMs = System.currentTimeMillis()
                val gap = if (lastGpsFixMs > 0L) (nowMs - lastGpsFixMs) / 1000f else 0f
                lastGpsFixMs = nowMs
                if (gap > 8f && !gpsStaleLogged) {
                    gpsStaleLogged = true
                    AppLogger.w("SD_GPS", "LOST gap=" + gap + "s (recovering)")
                }
                lastGpsSpeedTime = System.currentTimeMillis()
                if (loc.hasSpeed()) {
                    val newSpeed = loc.speed
                    lastGpsLat = loc.latitude
                    lastGpsLon = loc.longitude
                    lastGpsAccuracy = loc.accuracy
                    if (loc.hasBearing()) lastGpsBearing = loc.bearing
                    gpsStaleLogged = false
                    if (gap > 4f) {
                        AppLogger.w("SD_GPS", "STALE gap=" + gap + "s")
                    }
                    AppLogger.throttled(
                        "SD_GPS", "fix", 2000L,
                        "speed=" + newSpeed + " acc=" + smoothGpsAccel +
                            " accuracy=" + loc.accuracy + " gap=" + gap +
                            " bearing=" + (if (loc.hasBearing()) loc.bearing else "n/a") +
                            " pos=" + loc.latitude + "," + loc.longitude
                    )
                    val dt = if (prevGpsSpeedTimeMs > 0L) (nowMs - prevGpsSpeedTimeMs) / 1000f else 0f
                    if (dt in 0.2f..10f) {
                        val instAccelG = (newSpeed - prevGpsSpeedMs) / dt / G
                        val gpsTau = 2.5f
                        val gk = 1f - exp(-dt / gpsTau)
                        smoothGpsAccel += gk * (instAccelG - smoothGpsAccel)
                        if (forwardLocked && flipCount < 2 && newSpeed > 4.17f) {
                            val bRadNow = if (loc.hasBearing()) loc.bearing * (PI.toFloat() / 180f) else Float.NaN
                            val bearingStable = if (prevGpsBearingRad.isNaN() || bRadNow.isNaN()) true
                            else abs(wrapAngle(bRadNow - prevGpsBearingRad)) < 0.35f
                            val instA = instAccelG
                            val longS = smoothLongAccel
                            val strongSign = (instA > 0.15f && longS < -1.0f) || (instA < -0.15f && longS > 1.0f)
                            if (strongSign && bearingStable) {
                                val nowMsNow = System.currentTimeMillis()
                                if (gpsFlipVotes == 0 || nowMsNow - gpsFlipVoteStartMs > 30_000L) {
                                    gpsFlipVotes = 1
                                    gpsFlipVoteStartMs = nowMsNow
                                } else {
                                    gpsFlipVotes++
                                }
                                if (gpsFlipVotes >= 2) {
                                    gpsFlipVotes = 0
                                    flipForward("gps")
                                }
                            } else {
                                gpsFlipVotes = 0
                            }
                            if (!bRadNow.isNaN()) prevGpsBearingRad = bRadNow
                        }
                    }
                    if (forwardLocked && loc.hasBearing() && newSpeed > 2.78f && loc.accuracy <= 30f) {
                        val bearingRad = loc.bearing * (PI.toFloat() / 180f)
                        if (!bearingOffsetValid && !cornerActive && abs(smoothYawRate) < 0.08f) {
                            val phi = wrapAngle(atan2(worldForward[0], worldForward[1]) - bearingRad)
                            val ref = if (hasLockedOffset)
                                wrapAngle(lastLockedOffset + if (flipCount % 2 == 1) PI.toFloat() else 0f)
                            else phi
                            if (!hasLockedOffset && abs(phi) >= 0.35f) {
                                bearingOffsetVotes = 0
                            } else if (bearingOffsetVotes == 0) {
                                bearingOffset = ref
                                bearingOffsetVotes = 1
                            } else if (abs(phi - bearingOffset) < 0.25f) {
                                bearingOffsetVotes++
                                if (bearingOffsetVotes >= 3) {
                                    bearingOffsetValid = true
                                    bearingOffsetFrameGame = hasGameRotation
                                    bearingOffsetVotes = 0
                                    lastLockedOffset = wrapAngle(bearingOffset)
                                    hasLockedOffset = true
                                    AppLogger.i("SD_FWD", "bearing offset locked off=" + bearingOffset)
                                }
                            } else {
                                bearingOffsetVotes = 0
                            }
                        } else if (bearingOffsetValid && !cornerActive && bearingOffsetFrameGame == hasGameRotation) {
                            val decayStep = 0.02f * ((nowMs - prevGpsSpeedTimeMs).coerceIn(0L, 5000L)) / 1000f
                            if (abs(bearingOffset) <= decayStep) {
                                bearingOffset = 0f
                            } else {
                                bearingOffset -= decayStep * if (bearingOffset > 0f) 1f else -1f
                            }
                            lastLockedOffset = wrapAngle(bearingOffset)
                            val tx = sin(bearingRad + bearingOffset)
                            val ty = cos(bearingRad + bearingOffset)
                            val dot = worldForward[0] * tx + worldForward[1] * ty
                            if (dot > 0f) {
                                val cross = worldForward[0] * ty - worldForward[1] * tx
                                val err = atan2(cross, dot)
                                if (gpsBearingVotes == 0 || abs(err - gpsBearingErr) < 0.5f) {
                                    gpsBearingErr = err
                                    gpsBearingVotes++
                                    if (gpsBearingVotes >= 2) {
                                        gpsBearingVotes = 0
                                        val dTheta = (err * 0.25f).coerceIn(-0.05f, 0.05f)
                                        rotateForward(dTheta)
                                        if (abs(dTheta) > 0.003f) {
                                            AppLogger.throttled(
                                                "SD_FWD", "gpsBearing", 2000L,
                                                "dTheta=" + dTheta + " off=" + bearingOffset
                                            )
                                        }
                                    }
                                } else {
                                    gpsBearingVotes = 0
                                }
                            } else {
                                val cross = worldForward[0] * ty - worldForward[1] * tx
                                val err = atan2(cross, dot)
                                val flipped = abs(abs(err) - PI.toFloat()) < 0.35f
                                if (flipped) {
                                    if (gpsBearingVotes == 0 || abs(err - gpsBearingErr) < 0.5f) {
                                        gpsBearingErr = err
                                        gpsBearingVotes++
                                        if (gpsBearingVotes >= 2) {
                                            gpsBearingVotes = 0
                                            flipForward("bearing-escape")
                                        }
                                    } else {
                                        gpsBearingVotes = 0
                                    }
                                } else {
                                    gpsBearingVotes = 0
                                }
                            }
                        }
                    }
                    prevGpsSpeedMs = newSpeed
                    prevGpsSpeedTimeMs = nowMs
                    gpsSpeedMs = newSpeed
                    lastGoodSpeedMs = newSpeed
                }
            }
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, 1000L, 1f, listener)
            } catch (e: SecurityException) {
                AppLogger.w("SD_GPS", "SECURITY_EXCEPTION provider=" + provider)
            } catch (_: Throwable) {}
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
}

private fun maxOf(a: Float, b: Float) = if (a > b) a else b

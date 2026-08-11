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
import kotlin.math.abs
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

    private val forwardCandidates = arrayOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
    )
    private val candidateVotes = IntArray(6)
    private var forwardLocked = false
    private val worldForward = FloatArray(3)
    private var calibAccX = 0f
    private var calibAccY = 0f
    private var calibTime = 0f

    private var brakeType = BrakeType.FRICTION
    private var verticalSmoothnessLp = 0.5f

    fun start() {
        val sm = sensorManager ?: return
        gameRotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(gameRotMatrix, event.values)
                hasGameRotation = true
                if (linAccelValid) processWorldAccel()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!hasGameRotation) {
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
                if (hasGameRotation || hasRotation) {
                    val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
                    val wz = mat[8] * event.values[0] + mat[9] * event.values[1] + mat[10] * evZ
                    val nowNs = System.nanoTime()
                    val dt = if (lastYawSmoothNs == 0L) 0.016f else ((nowNs - lastYawSmoothNs) / 1e9f).coerceIn(0.001f, 0.3f)
                    lastYawSmoothNs = nowNs
                    prevYawRate = smoothYawRate
                    val profile = soundDriveConfig.effectiveSensorProfile
                    val tau = (profile.inputTauMs / 1000f).coerceAtLeast(0.02f)
                    val k = 1f - exp(-dt / tau)
                    smoothYawRate += k * (wz - smoothYawRate)
                    yawRateTrend = smoothYawRate - prevYawRate
                    if (forwardLocked) rotateForward(wz * dt)
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
            longAccel = wx * f[0] + wy * f[1]
            latAccel = wx * f[1] - wy * f[0]
            updateForwardCalibration(wx, wy, dt)
        } else {
            detectForwardAxis(wx, wy)
            longAccel = wy
            latAccel = wx
        }

        longAccel = longAccel.sanitize()
        latAccel = latAccel.sanitize()

        val gpsOnly = soundDriveConfig.gpsMode

        val k = 1f - exp(-dt / tau)

        smoothLongAccel += k * (longAccel - smoothLongAccel).sanitize()
        smoothLatAccel += k * (latAccel - smoothLatAccel).sanitize()

        val longG = abs(smoothLongAccel.sanitize()) / G
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

        val rawAccelIntensity = (longG * profile.accelSensitivity).coerceIn(0f, 1f).sanitize()
        val accelIntensity = if (rawAccelIntensity < 0.06f) 0f else rawAccelIntensity
        val cornerLat = ((latG / 0.8f) * profile.cornerSensitivity).coerceIn(0f, 1f).sanitize()

        val braking = smoothLongAccel.sanitize() < -1.2f
        val brakeIntensity = if (braking) ((-smoothLongAccel / (3f * G)).coerceIn(0f, 1f) * profile.accelSensitivity).sanitize() else 0f

        computeRoadRoughness(wz.sanitize(), profile.bumpFiltering)
        computeCornerPrediction(profile.cornerPredictionS)
        updateAmbientMood()
        updateBrakeType()

        roadRoughness = roadRoughness.sanitize()
        verticalJounce = verticalJounce.sanitize()
        hillGrade = hillGrade.sanitize()
        ambientMood = ambientMood.sanitize(0.5f)

        val cornerTotal = maxOf(cornerLat, cornerPrediction).coerceIn(0f, 1f).sanitize()

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

        lastState = lastState.copy(
            speed = gpsSpeedMs, speedKmh = speedKmh,
            accelIntensity = effectiveAccel, brakeIntensity = effectiveBrake,
            cornerIntensity = cornerTotal,
            drivingState = drivingState,
            volumeDrums = mixer.volumeDrums, volumeBass = mixer.volumeBass,
            volumeOther = mixer.volumeOther, volumeVocals = mixer.volumeVocals,
            soundDriveEnabled = soundDriveConfig.enabled,
            soundDriveMode = soundDriveConfig.mode,
            soundDriveIntensity = soundDriveConfig.intensity,
            gestureIndicator = lastGesture,
            roadRoughness = roadRoughness, ambientMood = ambientMood,
            hillGrade = hillGrade, brakeType = brakeType,
            gpsMode = soundDriveConfig.gpsMode,
            gpsStatus = currentGpsStatus()
        )
        onStateUpdate(lastState)
    }

    private fun detectForwardAxis(wx: Float, wy: Float) {
        if (forwardLocked || abs(smoothYawRate) > 0.15f) return
        val mag = sqrt(wx * wx + wy * wy)
        if (mag < 0.15f * G) return
        val mat = if (hasGameRotation) gameRotMatrix else rotMatrix
        val hx = wx / mag
        val hy = wy / mag
        var best = -1
        var bestScore = -1f
        for (i in forwardCandidates.indices) {
            val ax = forwardCandidates[i]
            val awx = mat[0] * ax[0] + mat[1] * ax[1] + mat[2] * ax[2]
            val awy = mat[4] * ax[0] + mat[5] * ax[1] + mat[6] * ax[2]
            val hlen = sqrt(awx * awx + awy * awy)
            if (hlen < 0.2f) continue
            val score = (hx * awx + hy * awy) / hlen
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0 || bestScore < 0.85f) {
            candidateVotes.fill(0)
            return
        }
        candidateVotes[best]++
        if (candidateVotes[best] >= 15) {
            val ax = forwardCandidates[best]
            val awx = mat[0] * ax[0] + mat[1] * ax[1] + mat[2] * ax[2]
            val awy = mat[4] * ax[0] + mat[5] * ax[1] + mat[6] * ax[2]
            val hlen = sqrt(awx * awx + awy * awy)
            worldForward[0] = awx / hlen
            worldForward[1] = awy / hlen
            worldForward[2] = 0f
            forwardLocked = true
            AppLogger.i(
                "SD_FWD",
                "forward axis locked=" + best + " fwd=(" + worldForward[0] + "," + worldForward[1] + ")"
            )
        }
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

    private fun updateForwardCalibration(wx: Float, wy: Float, dt: Float) {
        if (abs(smoothYawRate) > 0.1f) {
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
            val targetX = calibAccX / mag
            val targetY = calibAccY / mag
            val cross = worldForward[0] * targetY - worldForward[1] * targetX
            val dTheta = (cross * 0.2f).coerceIn(-0.02f, 0.02f)
            rotateForward(dTheta)
            if (abs(dTheta) > 0.005f) {
                AppLogger.i(
                    "SD_FWD",
                    "calib dTheta=" + dTheta + " fwd=(" + worldForward[0] + "," + worldForward[1] + ")"
                )
            }
        }
        calibAccX = 0f
        calibAccY = 0f
        calibTime = 0f
    }

    private fun processFallback() {
        val gravityNorm = rawAccelZ.coerceAtLeast(1f)
        val lateralG = abs(rawAccelX) / gravityNorm
        val longG = abs(rawAccelY) / gravityNorm
        val speedKmh = gpsSpeedMs * 3.6f
        val speedGate = (speedKmh / 58f).coerceIn(0f, 1f)

        val accelIntensity = longG.coerceIn(0f, 1f)
        val cornerIntensity = (lateralG / 0.8f).coerceIn(0f, 1f)

        val braking = rawAccelY < -0.5f
        val brakeIntensity = if (braking) (-rawAccelY / 3f).coerceIn(0f, 1f) else 0f

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
            soundDriveIntensity = soundDriveConfig.intensity,
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
        AppLogger.i("SD_GPS", "ENABLE fine=$hasFine coarse=$hasCoarse")
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
                    gpsStaleLogged = false
                    if (gap > 4f) {
                        AppLogger.w("SD_GPS", "STALE gap=" + gap + "s")
                    }
                    AppLogger.throttled(
                        "SD_GPS", "fix", 2000L,
                        "speed=" + newSpeed + " acc=" + smoothGpsAccel +
                            " accuracy=" + loc.accuracy + " gap=" + gap
                    )
                    val dt = if (prevGpsSpeedTimeMs > 0L) (nowMs - prevGpsSpeedTimeMs) / 1000f else 0f
                    if (dt in 0.2f..10f) {
                        val instAccelG = (newSpeed - prevGpsSpeedMs) / dt / G
                        val gpsTau = 2.5f
                        val gk = 1f - exp(-dt / gpsTau)
                        smoothGpsAccel += gk * (instAccelG - smoothGpsAccel)
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

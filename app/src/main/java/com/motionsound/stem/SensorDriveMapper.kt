package com.motionsound.stem

import android.content.Context
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
    private val light = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
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

    private var rawAccelX = 0f
    private var rawAccelY = 0f
    private var rawAccelZ = 9.81f
    private var rawGyroZ = 0f

    private val G = 9.81f

    @Volatile private var gpsSpeedMs = 0f
    @Volatile private var gpsBearing = 0f
    @Volatile private var gpsHasBearing = false
    private var lastGpsBearing = 0f
    private var lastGpsTime = 0L

    private var lastState = StemUiState()
    private var lastGesture: GestureType? = null

    private var roadRoughness = 0f
    private var verticalJounce = 0f
    private var verticalLp = 0f
    private val verticalBuffer = FloatArray(20)
    private var verticalIdx = 0
    private var jounceDecay = 0f

    private var currentLux = 500f
    private var ambientMood = 0.5f

    private var lastPressure = 1013.25f
    private var lastPressureTime = 0L
    private var hillGrade = 0f

    private var cornerPrediction = 0f
    private var prevYawRate = 0f
    private var yawRateTrend = 0f

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
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
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
                    prevYawRate = smoothYawRate
                    val profile = soundDriveConfig.effectiveSensorProfile
                    val rs = profile.responseSpeed
                    smoothYawRate += rs * (wz - smoothYawRate)
                    yawRateTrend = smoothYawRate - prevYawRate
                } else {
                    rawGyroZ += 0.15f * (evZ - rawGyroZ)
                }
            }
            Sensor.TYPE_LIGHT -> {
                currentLux = event.values[0]
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

        if (gpsHasBearing) {
            val bearingRad = Math.toRadians(gpsBearing.toDouble()).toFloat()
            val forwardX = sin(bearingRad); val forwardY = cos(bearingRad)
            longAccel = wx * forwardX + wy * forwardY
            latAccel = wx * forwardY - wy * forwardX
        } else {
            longAccel = wy
            latAccel = wx
        }

        longAccel = longAccel.sanitize()
        latAccel = latAccel.sanitize()

        val profile = soundDriveConfig.effectiveSensorProfile
        val rs = profile.responseSpeed

        smoothLongAccel += rs * (longAccel - smoothLongAccel).sanitize()
        smoothLatAccel += rs * (latAccel - smoothLatAccel).sanitize()

        val longG = abs(smoothLongAccel.sanitize()) / G
        val latG = abs(smoothLatAccel.sanitize()) / G
        val speedKmh = (gpsSpeedMs * 3.6f).sanitize()
        val speedGate = (speedKmh / 58f).coerceIn(0f, 1f).sanitize()

        val accelIntensity = (longG * profile.accelSensitivity).coerceIn(0f, 1f).sanitize()
        val cornerLat = ((latG / 0.8f) * profile.cornerSensitivity).coerceIn(0f, 1f).sanitize()

        val braking = smoothLongAccel.sanitize() < -0.5f
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

        val drivingState = when {
            cornerTotal > 0.4f && smoothYawRate > 0.5f -> DrivingState.CORNERING
            accelIntensity > 0.3f -> DrivingState.ACCELERATING
            brakeIntensity > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        val processor = soundDriveProcessor
        if (processor != null) {
            lastGesture = processor.update(
                accelIntensity, brakeIntensity, cornerTotal, speedGate,
                drivingState, soundDriveConfig,
                roadRoughness, ambientMood, hillGrade, brakeType, verticalJounce
            )
        } else {
            mixer.volumeDrums = lerp(0f, 1f, maxOf(accelIntensity, cornerTotal * 0.8f))
            mixer.volumeBass = lerp(0f, 1f, maxOf(accelIntensity * 0.7f, speedGate * 0.9f))
            mixer.volumeOther = lerp(0.8f, 1f, cornerTotal * 0.5f)
            mixer.volumeVocals = lerp(0f, 1f, speedGate * 0.6f + accelIntensity * 0.2f)
            lastGesture = null
        }

        lastState = lastState.copy(
            speed = gpsSpeedMs, speedKmh = speedKmh,
            accelIntensity = accelIntensity, brakeIntensity = brakeIntensity,
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
            sensorProfile = soundDriveConfig.sensorProfile,
            maxSpeedKmh = soundDriveConfig.effectiveSensorProfile.maxSpeedKmh
        )
        onStateUpdate(lastState)
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
            lastGesture = processor.update(
                accelIntensity, brakeIntensity, cornerIntensity, speedGate,
                drivingState, soundDriveConfig,
                0f, 0.5f, 0f, BrakeType.FRICTION, 0f
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
            sensorProfile = soundDriveConfig.sensorProfile,
            maxSpeedKmh = soundDriveConfig.effectiveSensorProfile.maxSpeedKmh
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
        val isNight = hour in 22..23 || hour in 0..5
        ambientMood = when {
            isNight && currentLux < 10 -> 0.0f
            currentLux < 5 -> 0.2f
            currentLux in 5f..50f -> 0.4f
            currentLux in 50f..500f -> 0.6f
            else -> 0.7f
        }
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
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 1f,
                object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (loc.hasSpeed()) gpsSpeedMs = loc.speed
                        if (loc.hasBearing()) {
                            lastGpsBearing = gpsBearing
                            gpsBearing = loc.bearing
                            if (!gpsHasBearing) gpsHasBearing = true
                        }
                    }
                }
            )
        } catch (_: SecurityException) {}
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
}

private fun maxOf(a: Float, b: Float) = if (a > b) a else b

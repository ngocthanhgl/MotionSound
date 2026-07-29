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

class SensorDriveMapper(
    private val context: Context,
    private val mixer: StemMixer,
    private val onStateUpdate: (StemUiState) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotVec = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    var soundDriveProcessor: SoundDriveProcessor? = null
    var soundDriveConfig: SoundDriveConfig = SoundDriveConfig()

    private val rotMatrix = FloatArray(16)
    private val linearWorld = FloatArray(3)
    private var hasRotation = false

    private var smoothLongAccel = 0f
    private var smoothLatAccel = 0f
    private var smoothYawRate = 0f

    private var rawAccelX = 0f
    private var rawAccelY = 0f
    private var rawAccelZ = 9.8f
    private var rawGyroZ = 0f

    private val alpha = 0.15f
    private val G = 9.81f

    @Volatile private var gpsSpeedMs = 0f
    @Volatile private var gpsBearing = 0f
    @Volatile private var gpsHasBearing = false

    private var lastState = StemUiState()
    private var lastGesture: GestureType? = null

    fun start() {
        val sm = sensorManager ?: return
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                hasRotation = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (hasRotation) {
                    val rx = event.values[0]
                    val ry = event.values[1]
                    val rz = event.values[2]

                    val wx = rotMatrix[0] * rx + rotMatrix[1] * ry + rotMatrix[2] * rz
                    val wy = rotMatrix[4] * rx + rotMatrix[5] * ry + rotMatrix[6] * rz
                    val wz = rotMatrix[8] * rx + rotMatrix[9] * ry + rotMatrix[10] * rz

                    linearWorld[0] = wx
                    linearWorld[1] = wy
                    linearWorld[2] = wz + G

                    updateMixerVolumes()
                } else {
                    rawAccelX += alpha * (event.values[0] - rawAccelX)
                    rawAccelY += alpha * (event.values[1] - rawAccelY)
                    rawAccelZ += alpha * (event.values[2] - rawAccelZ)
                    updateMixerVolumesFallback()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (hasRotation) {
                    val wz = rotMatrix[8] * event.values[0] +
                             rotMatrix[9] * event.values[1] +
                             rotMatrix[10] * event.values[2]
                    smoothYawRate += alpha * (wz - smoothYawRate)
                } else {
                    rawGyroZ += alpha * (event.values[2] - rawGyroZ)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateMixerVolumes() {
        val longAccel: Float
        val latAccel: Float

        if (gpsHasBearing) {
            val bearingRad = Math.toRadians(gpsBearing.toDouble()).toFloat()
            val forwardX = sin(bearingRad)
            val forwardY = cos(bearingRad)

            longAccel = linearWorld[0] * forwardX + linearWorld[1] * forwardY
            latAccel = linearWorld[0] * forwardY - linearWorld[1] * forwardX
        } else {
            longAccel = linearWorld[1]
            latAccel = linearWorld[0]
        }

        smoothLongAccel += alpha * (longAccel - smoothLongAccel)
        smoothLatAccel += alpha * (latAccel - smoothLatAccel)

        val longG = abs(smoothLongAccel) / G
        val latG = abs(smoothLatAccel) / G
        val yawRateDeg = abs(smoothYawRate)
        val speedKmh = gpsSpeedMs * 3.6f

        val accelIntensity = longG.coerceIn(0f, 1f)
        val cornerIntensity = (latG / 0.8f).coerceIn(0f, 1f)
        val speedIntensity = (speedKmh / 130f).coerceIn(0f, 1f)

        val braking = smoothLongAccel < -0.5f
        val brakeIntensity = if (braking) (-smoothLongAccel / (3f * G)).coerceIn(0f, 1f) else 0f

        val drivingState = when {
            cornerIntensity > 0.4f && yawRateDeg > 0.5f -> DrivingState.CORNERING
            accelIntensity > 0.3f -> DrivingState.ACCELERATING
            brakeIntensity > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        val processor = soundDriveProcessor
        if (processor != null) {
            lastGesture = processor.update(
                accelIntensity, brakeIntensity, cornerIntensity, speedIntensity,
                drivingState, soundDriveConfig
            )
        } else {
            mixer.volumeDrums = lerp(0f, 1f, maxOf(accelIntensity, cornerIntensity * 0.8f))
            mixer.volumeBass = lerp(0f, 1f, maxOf(accelIntensity * 0.7f, speedIntensity * 0.9f))
            mixer.volumeOther = lerp(0.8f, 1f, cornerIntensity * 0.5f)
            mixer.volumeVocals = lerp(0f, 1f, speedIntensity * 0.6f + accelIntensity * 0.2f)
            lastGesture = null
        }

        lastState = lastState.copy(
            speed = gpsSpeedMs,
            speedKmh = speedKmh,
            accelIntensity = accelIntensity,
            brakeIntensity = brakeIntensity,
            cornerIntensity = cornerIntensity,
            drivingState = drivingState,
            volumeDrums = mixer.volumeDrums,
            volumeBass = mixer.volumeBass,
            volumeOther = mixer.volumeOther,
            volumeVocals = mixer.volumeVocals,
            soundDriveEnabled = soundDriveConfig.enabled,
            soundDriveMode = soundDriveConfig.mode,
            soundDriveIntensity = soundDriveConfig.intensity,
            gestureIndicator = lastGesture
        )
        onStateUpdate(lastState)
    }

    private fun updateMixerVolumesFallback() {
        val gravityNorm = rawAccelZ.coerceAtLeast(1f)
        val lateralG = abs(rawAccelX) / gravityNorm
        val longG = abs(rawAccelY) / gravityNorm
        val yawRate = abs(rawGyroZ)
        val speedKmh = gpsSpeedMs * 3.6f

        val accelIntensity = longG.coerceIn(0f, 1f)
        val cornerIntensity = (lateralG / 0.8f).coerceIn(0f, 1f)
        val speedIntensity = (speedKmh / 130f).coerceIn(0f, 1f)

        val braking = rawAccelY < -0.5f
        val brakeIntensity = if (braking) (-rawAccelY / 3f).coerceIn(0f, 1f) else 0f

        val drivingState = when {
            cornerIntensity > 0.4f && yawRate > 0.5f -> DrivingState.CORNERING
            accelIntensity > 0.3f -> DrivingState.ACCELERATING
            brakeIntensity > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        val processor = soundDriveProcessor
        if (processor != null) {
            lastGesture = processor.update(
                accelIntensity, brakeIntensity, cornerIntensity, speedIntensity,
                drivingState, soundDriveConfig
            )
        } else {
            mixer.volumeDrums = lerp(0f, 1f, maxOf(accelIntensity, cornerIntensity * 0.8f))
            mixer.volumeBass = lerp(0f, 1f, maxOf(accelIntensity * 0.7f, speedIntensity * 0.9f))
            mixer.volumeOther = lerp(0.8f, 1f, cornerIntensity * 0.5f)
            mixer.volumeVocals = lerp(0f, 1f, speedIntensity * 0.6f + accelIntensity * 0.2f)
            lastGesture = null
        }

        lastState = lastState.copy(
            speed = gpsSpeedMs,
            speedKmh = speedKmh,
            accelIntensity = accelIntensity,
            brakeIntensity = brakeIntensity,
            cornerIntensity = cornerIntensity,
            drivingState = drivingState,
            volumeDrums = mixer.volumeDrums,
            volumeBass = mixer.volumeBass,
            volumeOther = mixer.volumeOther,
            volumeVocals = mixer.volumeVocals,
            soundDriveEnabled = soundDriveConfig.enabled,
            soundDriveMode = soundDriveConfig.mode,
            soundDriveIntensity = soundDriveConfig.intensity,
            gestureIndicator = lastGesture
        )
        onStateUpdate(lastState)
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
                            gpsBearing = loc.bearing
                            gpsHasBearing = true
                        }
                    }
                }
            )
        } catch (_: SecurityException) {}
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
}

private fun maxOf(a: Float, b: Float) = if (a > b) a else b

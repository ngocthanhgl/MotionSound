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
import kotlin.math.abs

class SensorDriveMapper(
    private val context: Context,
    private val mixer: StemMixer,
    private val onStateUpdate: (StemUiState) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var smoothAccelX = 0f
    private var smoothAccelY = 0f
    private var smoothAccelZ = 9.8f
    private var smoothGyroZ = 0f

    private val alpha = 0.15f

    @Volatile private var gpsSpeedMs = 0f

    private var lastState = StemUiState()

    fun start() {
        val sm = sensorManager ?: return
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                smoothAccelX += alpha * (event.values[0] - smoothAccelX)
                smoothAccelY += alpha * (event.values[1] - smoothAccelY)
                smoothAccelZ += alpha * (event.values[2] - smoothAccelZ)
            }
            Sensor.TYPE_GYROSCOPE -> {
                smoothGyroZ += alpha * (event.values[2] - smoothGyroZ)
            }
        }
        updateMixerVolumes()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateMixerVolumes() {
        val gravityNorm = smoothAccelZ.coerceAtLeast(1f)
        val lateralG = abs(smoothAccelX) / gravityNorm
        val longG = abs(smoothAccelY) / gravityNorm
        val yawRate = abs(smoothGyroZ)
        val speedKmh = gpsSpeedMs * 3.6f

        val accelIntensity = longG.coerceIn(0f, 1f)
        val cornerIntensity = (lateralG / 0.8f).coerceIn(0f, 1f)
        val speedIntensity = (speedKmh / 130f).coerceIn(0f, 1f)

        val braking = smoothAccelY < -0.5f
        val brakeIntensity = if (braking) (-smoothAccelY / 3f).coerceIn(0f, 1f) else 0f

        val drivingState = when {
            cornerIntensity > 0.4f && yawRate > 0.5f -> DrivingState.CORNERING
            accelIntensity > 0.3f -> DrivingState.ACCELERATING
            brakeIntensity > 0.3f -> DrivingState.DECELERATING
            speedKmh > 5f -> DrivingState.CRUISING
            speedKmh > 1f -> DrivingState.SLOW_MANEUVERING
            else -> DrivingState.IDLE
        }

        mixer.volumeDrums = lerp(1.0f, 1.5f, maxOf(accelIntensity, cornerIntensity * 0.8f))
        mixer.volumeBass = lerp(1.0f, 1.5f, maxOf(accelIntensity * 0.7f, speedIntensity * 0.9f))
        mixer.volumeOther = lerp(1.0f, 1.3f, cornerIntensity * 0.5f)
        mixer.volumeVocals = lerp(1.0f, 0.5f, speedIntensity * 0.6f + accelIntensity * 0.2f)

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
            volumeVocals = mixer.volumeVocals
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
                    }
                }
            )
        } catch (_: SecurityException) {}
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
}

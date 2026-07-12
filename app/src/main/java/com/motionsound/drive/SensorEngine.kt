package com.motionsound.drive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

data class SensorFrame(
    val timestampNanos: Long,
    val accel: FloatArray,
    val gyro: FloatArray,
    val linearAccel: FloatArray?,
    val gravity: FloatArray?,
    val mag: FloatArray?,
    val gpsSpeed: Float,
    val gpsBearing: Float,
    val gpsAccuracy: Float,
    val gpsTime: Long
)

class SensorEngine(private val context: Context) {

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var registered = false

    private val latestAccel = AtomicReference(FloatArray(3))
    private val latestGyro = AtomicReference(FloatArray(3))
    private val latestLinear = AtomicReference<FloatArray?>(null)
    private val latestGravity = AtomicReference<FloatArray?>(null)
    private val latestMag = AtomicReference<FloatArray?>(null)
    private val latestTimestamp = AtomicReference(0L)

    private val latestGpsSpeed = AtomicReference(0f)
    private val latestGpsBearing = AtomicReference(0f)
    private val latestGpsAccuracy = AtomicReference(-1f)
    private val latestGpsTime = AtomicReference(0L)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values.clone()
            val ts = event.timestamp
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAccel.set(values)
                    latestTimestamp.set(ts)
                }
                Sensor.TYPE_GYROSCOPE -> latestGyro.set(values)
                Sensor.TYPE_MAGNETIC_FIELD -> latestMag.set(values)
                Sensor.TYPE_LINEAR_ACCELERATION -> latestLinear.set(values)
                Sensor.TYPE_GRAVITY -> latestGravity.set(values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestGpsSpeed.set(location.speed)
            latestGpsBearing.set(location.bearing)
            latestGpsAccuracy.set(location.accuracy)
            latestGpsTime.set(location.elapsedRealtimeNanos / 1_000_000L)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val sm = sensorManager ?: return
        sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        val linearSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearSensor != null) sm.registerListener(sensorListener, linearSensor, SensorManager.SENSOR_DELAY_GAME)
        val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor != null) sm.registerListener(sensorListener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        registered = true

        try {
            val lm = locationManager ?: return
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, DrivingConfig.GPS_INTERVAL_MS, 0f, gpsListener, null)
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        if (registered) {
            sensorManager?.unregisterListener(sensorListener)
            registered = false
        }
        try {
            locationManager?.removeUpdates(gpsListener)
        } catch (_: SecurityException) {
        }
    }

    fun read(): SensorFrame? {
        val ts = latestTimestamp.get()
        if (ts == 0L) return null
        return SensorFrame(
            timestampNanos = ts,
            accel = latestAccel.get(),
            gyro = latestGyro.get(),
            linearAccel = latestLinear.get(),
            gravity = latestGravity.get(),
            mag = latestMag.get(),
            gpsSpeed = latestGpsSpeed.get(),
            gpsBearing = latestGpsBearing.get(),
            gpsAccuracy = latestGpsAccuracy.get(),
            gpsTime = latestGpsTime.get()
        )
    }
}

package com.motionsound.drive

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogger(private val context: Context) {

    private var writer: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private var isActive = false

    fun start() {
        if (isActive) return
        try {
            cleanupOldLogs()
            val fileName = "drive_log_${dateFormat.format(Date())}.csv"
            val file = File(context.filesDir, fileName)
            writer = FileWriter(file, false)
            writer?.appendLine(
                "timestamp,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z," +
                "gps_speed,gps_bearing,gps_accuracy," +
                "a_E,a_N,a_U,theta,a_long_filt,a_lat_filt," +
                "driving_state,accel_i,brake_i,corner_i,confidence," +
                "eq_band_0,eq_band_1,eq_band_2,eq_band_3,eq_band_4"
            )
            isActive = true
        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to start logger", e)
        }
    }

    private fun cleanupOldLogs() {
        try {
            val files = getLogFiles().sortedByDescending { it.lastModified() }
            if (files.size > 5) {
                for (f in files.drop(5)) f.delete()
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "cleanupOldLogs failed", e)
        }
    }

    fun log(
        sensorFrame: SensorFrame,
        aWorld: FloatArray,
        heading: Float,
        filtered: FilteredMotionFrame,
        classifierOut: ClassifierOutput,
        eqGains: FloatArray?
    ) {
        if (!isActive) return
        try {
            val w = writer ?: return
            w.appendLine(
                "${sensorFrame.timestampNanos}," +
                "${sensorFrame.accel[0]},${sensorFrame.accel[1]},${sensorFrame.accel[2]}," +
                "${sensorFrame.gyro[0]},${sensorFrame.gyro[1]},${sensorFrame.gyro[2]}," +
                "${sensorFrame.gpsSpeed},${sensorFrame.gpsBearing},${sensorFrame.gpsAccuracy}," +
                "${aWorld[0]},${aWorld[1]},${aWorld[2]},$heading," +
                "${filtered.aLongFilt},${filtered.aLatFilt}," +
                "${classifierOut.state},${classifierOut.accelIntensity},${classifierOut.brakeIntensity}," +
                "${classifierOut.cornerIntensity},${classifierOut.confidence}," +
                "${eqGains?.getOrElse(0) { 0f }},${eqGains?.getOrElse(1) { 0f }}," +
                "${eqGains?.getOrElse(2) { 0f }},${eqGains?.getOrElse(3) { 0f }}," +
                "${eqGains?.getOrElse(4) { 0f }}"
            )
            w.flush()
        } catch (e: Exception) {
            Log.e("DebugLogger", "log write failed", e)
        }
    }

    fun stop() {
        isActive = false
        try {
            writer?.close()
        } catch (e: Exception) {
            Log.e("DebugLogger", "stop failed", e)
        }
        writer = null
    }

    fun getLogFiles(): List<File> {
        return context.filesDir.listFiles()
            ?.filter { it.name.startsWith("drive_log_") && it.name.endsWith(".csv") }
            ?: emptyList()
    }
}

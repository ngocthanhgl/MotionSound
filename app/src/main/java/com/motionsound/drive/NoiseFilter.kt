package com.motionsound.drive

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class FilteredMotionFrame(
    val aLongFilt: Float,
    val aLatFilt: Float,
    val aVertFilt: Float,
    val confidence: Float,
    val bumpFlag: Boolean,
    val timestamp: Long
)

class NoiseFilter(lpfCutoffHz: Float = DrivingConfig.LPF_CUTOFF_HZ_CAR) {

    private val longLpf = Butterworth2nd(lpfCutoffHz, DrivingConfig.SENSOR_RATE_HZ.toFloat())
    private val latLpf = Butterworth2nd(lpfCutoffHz, DrivingConfig.SENSOR_RATE_HZ.toFloat())
    private val vertHpf = HighPass1st(0.5f, DrivingConfig.SENSOR_RATE_HZ.toFloat())

    private val longMedian = MedianFilter(5)
    private val latMedian = MedianFilter(5)

    private var vertRmsAccum = 0.0
    private var vertRmsCount = 0
    private val vertRmsWindow = (DrivingConfig.BUMP_ADAPTIVE_WINDOW_S * DrivingConfig.SENSOR_RATE_HZ).toInt()
    private var bumpHoldUntil = 0L

    private var prevLongFilt = 0f
    private var prevLatFilt = 0f

    fun filter(frame: MotionFrame, speed: Float, gyroZ: Float, timestampNanos: Long): FilteredMotionFrame {
        if (timestampNanos < bumpHoldUntil) {
            return FilteredMotionFrame(
                aLongFilt = prevLongFilt,
                aLatFilt = prevLatFilt,
                aVertFilt = 0f,
                confidence = 0.5f,
                bumpFlag = true,
                timestamp = frame.timestamp
            )
        }

        val aLongM = longMedian.filter(frame.aLong)
        val aLatM = latMedian.filter(frame.aLat)

        val aLongLpf = longLpf.filter(aLongM)
        val aLatLpf = latLpf.filter(aLatM)

        prevLongFilt = aLongLpf
        prevLatFilt = aLatLpf

        val vertHp = vertHpf.filter(frame.aVert)
        val vertHpAbs = abs(vertHp)
        vertRmsAccum += (vertHpAbs * vertHpAbs).toDouble()
        vertRmsCount++
        if (vertRmsCount > vertRmsWindow) {
            val excess = vertRmsCount - vertRmsWindow
            vertRmsAccum *= vertRmsWindow.toDouble() / vertRmsCount.toDouble()
            vertRmsCount = vertRmsWindow
        }
        val vertRms = if (vertRmsCount > 0) sqrt(vertRmsAccum / vertRmsCount) else 0.0
        val bumpThreshold = (vertRms * 3.0).coerceAtLeast(2.0)
        val isBump = vertHpAbs > bumpThreshold && vertHpAbs > 4.0

        if (isBump) {
            bumpHoldUntil = timestampNanos + DrivingConfig.BUMP_HOLD_MS * 1_000_000L
            return FilteredMotionFrame(
                aLongFilt = prevLongFilt,
                aLatFilt = prevLatFilt,
                aVertFilt = 0f,
                confidence = 0.5f,
                bumpFlag = true,
                timestamp = frame.timestamp
            )
        }

        var confidence = 1.0f
        val expectedLat = speed * gyroZ
        val latDivergence = abs(abs(aLatLpf) - abs(expectedLat))
        if (latDivergence > 2.0f && speed > DrivingConfig.MIN_SPEED_FOR_COURSE_MPS) {
            confidence *= (1f - (latDivergence / 8f).coerceAtMost(0.5f))
        }

        return FilteredMotionFrame(
            aLongFilt = aLongLpf,
            aLatFilt = aLatLpf,
            aVertFilt = frame.aVert,
            confidence = confidence,
            bumpFlag = false,
            timestamp = frame.timestamp
        )
    }

    fun setCutoff(cutoffHz: Float) {
        longLpf.setCutoff(cutoffHz, DrivingConfig.SENSOR_RATE_HZ.toFloat())
        latLpf.setCutoff(cutoffHz, DrivingConfig.SENSOR_RATE_HZ.toFloat())
    }
}

class Butterworth2nd(private var cutoffHz: Float, private var sampleRateHz: Float) {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    init { computeCoeffs() }

    fun filter(input: Float): Float {
        val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = input; y2 = y1; y1 = out
        return out
    }

    fun setCutoff(freq: Float, sr: Float) {
        cutoffHz = freq; sampleRateHz = sr; computeCoeffs()
    }

    private fun computeCoeffs() {
        val w0 = 2.0 * Math.PI * cutoffHz / sampleRateHz
        val cosW = cos(w0).toFloat()
        val sinW = kotlin.math.sin(w0).toFloat()
        val alpha = sinW / (2f * 0.7071f)
        val norm = 1f / (1f + alpha)
        b0 = ((1f - cosW) / 2f) * norm
        b1 = (1f - cosW) * norm
        b2 = b0
        a1 = (-2f * cosW) * norm
        a2 = (1f - alpha) * norm
    }
}

class HighPass1st(private var cutoffHz: Float, private var sampleRateHz: Float) {
    private var rc = 1f / (2f * 3.14159265f * cutoffHz)
    private var dt = 1f / sampleRateHz
    private var alpha = rc / (rc + dt)
    private var prevInput = 0f
    private var prevOutput = 0f

    init { computeAlpha() }

    fun filter(input: Float): Float {
        val out = alpha * (prevOutput + input - prevInput)
        prevInput = input; prevOutput = out
        return out
    }

    private fun computeAlpha() {
        rc = 1f / (2f * 3.14159265f * cutoffHz)
        dt = 1f / sampleRateHz
        alpha = rc / (rc + dt)
    }
}

class MedianFilter(private val windowSize: Int) {
    private val buffer = FloatArray(windowSize)
    private var index = 0
    private var count = 0

    fun filter(input: Float): Float {
        buffer[index] = input
        index = (index + 1) % windowSize
        if (count < windowSize) count++
        val sorted = buffer.copyOf(count)
        sorted.sort()
        return sorted[sorted.size / 2]
    }
}

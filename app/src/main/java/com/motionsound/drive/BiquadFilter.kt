package com.motionsound.drive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadFilter {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    fun setPeaking(f0: Float, q: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val omega = 2f * PI.toFloat() * f0 / sampleRate
        val alpha = sin(omega) / (2f * q)
        val cosw = cos(omega)

        b0 = 1f + alpha * a
        b1 = -2f * cosw
        b2 = 1f - alpha * a
        val a0inv = 1f / (1f + alpha / a)
        a1 = -2f * cosw * a0inv
        a2 = (1f - alpha / a) * a0inv

        b0 *= a0inv
        b1 *= a0inv
        b2 *= a0inv
        resetHistory()
    }

    fun setLowShelf(f0: Float, q: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val omega = 2f * PI.toFloat() * f0 / sampleRate
        val sinW = sin(omega)
        val cosW = cos(omega)
        val alpha = sinW / (2f * q)
        val sqrtA = sqrt(a)
        val a0inv = 1f / ((a + 1f) + (a - 1f) * cosW + 2f * sqrtA * alpha)

        b0 = a * ((a + 1f) - (a - 1f) * cosW + 2f * sqrtA * alpha) * a0inv
        b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW) * a0inv
        b2 = a * ((a + 1f) - (a - 1f) * cosW - 2f * sqrtA * alpha) * a0inv
        a1 = -2f * ((a - 1f) + (a + 1f) * cosW) * a0inv
        a2 = ((a + 1f) + (a - 1f) * cosW - 2f * sqrtA * alpha) * a0inv
        resetHistory()
    }

    fun setHighShelf(f0: Float, q: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val omega = 2f * PI.toFloat() * f0 / sampleRate
        val sinW = sin(omega)
        val cosW = cos(omega)
        val alpha = sinW / (2f * q)
        val sqrtA = sqrt(a)
        val a0inv = 1f / ((a + 1f) - (a - 1f) * cosW + 2f * sqrtA * alpha)

        b0 = a * ((a + 1f) + (a - 1f) * cosW + 2f * sqrtA * alpha) * a0inv
        b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW) * a0inv
        b2 = a * ((a + 1f) + (a - 1f) * cosW - 2f * sqrtA * alpha) * a0inv
        a1 = 2f * ((a - 1f) - (a + 1f) * cosW) * a0inv
        a2 = ((a + 1f) - (a - 1f) * cosW - 2f * sqrtA * alpha) * a0inv
        resetHistory()
    }

    fun setLowPass(f0: Float, q: Float, sampleRate: Float) {
        val omega = 2f * PI.toFloat() * f0 / sampleRate
        val alpha = sin(omega) / (2f * q)
        val cosW = cos(omega)
        val norm = 1f / (1f + alpha)
        b0 = ((1f - cosW) / 2f) * norm
        b1 = (1f - cosW) * norm
        b2 = b0
        a1 = (-2f * cosW) * norm
        a2 = (1f - alpha) * norm
        resetHistory()
    }

    fun process(input: FloatArray) {
        for (i in input.indices) {
            val x = input[i]
            var y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (y.isNaN() || y.isInfinite()) y = 0f
            if (kotlin.math.abs(y1) < 1e-38f) y1 = 0f
            if (kotlin.math.abs(y2) < 1e-38f) y2 = 0f
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            input[i] = y
        }
    }

    fun resetHistory() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

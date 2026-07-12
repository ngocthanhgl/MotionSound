package com.motionsound.drive

import kotlin.math.exp

class ParameterSmoother(private val numParams: Int) {

    private val current = FloatArray(numParams)

    fun reset() {
        current.fill(0f)
    }

    fun resetTo(target: FloatArray) {
        for (i in 0 until numParams.coerceAtMost(target.size)) {
            current[i] = target[i]
        }
    }

    fun update(target: FloatArray, attackMs: Float, releaseMs: Float, dt: Float) {
        val dtSec = dt.coerceAtMost(0.1f)
        for (i in 0 until numParams.coerceAtMost(target.size)) {
            val tc = if (target[i] > current[i]) attackMs else releaseMs
            val alpha = if (tc > 0f) 1f - exp(-(dtSec * 1000f) / tc) else 1f
            current[i] += alpha.coerceIn(0f, 1f) * (target[i] - current[i])
        }
    }

    fun getCurrent(): FloatArray = current.copyOf()

    fun get(index: Int): Float = if (index in current.indices) current[index] else 0f

    companion object {
        fun computeAlphaMs(dtMs: Float, timeConstantMs: Float): Float {
            if (timeConstantMs <= 0f || dtMs <= 0f) return 1f
            return 1f - exp(-dtMs / timeConstantMs)
        }
    }
}

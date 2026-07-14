package com.motionsound.drive

import kotlin.math.floor

class ReverbEffect(sampleRate: Float) {
    private val combs = listOf(
        CombFilter((0.030f * sampleRate).toInt(), 0.6f),
        CombFilter((0.037f * sampleRate).toInt(), 0.55f),
        CombFilter((0.043f * sampleRate).toInt(), 0.5f),
        CombFilter((0.050f * sampleRate).toInt(), 0.45f),
    )
    private val allpasses = listOf(
        AllPassFilter((0.005f * sampleRate).toInt(), 0.7f),
        AllPassFilter((0.0017f * sampleRate).toInt(), 0.7f),
    )

    fun process(input: FloatArray, reverbMix: Float) {
        if (reverbMix <= 0f) return
        var nanFound = false
        for (i in input.indices) {
            if (!input[i].isFinite()) { nanFound = true; break }
        }
        if (nanFound) {
            for (i in input.indices) input[i] = 0f
            return
        }
        val wet = FloatArray(input.size)
        for (comb in combs) comb.process(input, wet)
        for (ap in allpasses) ap.process(wet)
        for (i in input.indices) {
            input[i] = input[i] * (1f - reverbMix) + wet[i] * reverbMix
        }
    }

    private class CombFilter(delaySamples: Int, private val feedback: Float) {
        private val buf = FloatArray(delaySamples.coerceAtLeast(1))
        private var idx = 0

        fun process(input: FloatArray, accum: FloatArray) {
            for (i in input.indices) {
                val read = buf[idx]
                buf[idx] = input[i] + read * feedback
                accum[i] += read
                idx = (idx + 1) % buf.size
            }
        }
    }

    private class AllPassFilter(delaySamples: Int, private val gain: Float) {
        private val buf = FloatArray(delaySamples.coerceAtLeast(1))
        private var idx = 0

        fun process(input: FloatArray) {
            for (i in input.indices) {
                val read = buf[idx]
                buf[idx] = input[i] + read * gain
                input[i] = read - input[i]
                idx = (idx + 1) % buf.size
            }
        }
    }
}

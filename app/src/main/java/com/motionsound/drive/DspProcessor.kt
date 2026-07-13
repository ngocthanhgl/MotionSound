package com.motionsound.drive

import kotlin.math.pow
import kotlin.math.sqrt

class DspProcessor(private val sampleRate: Float) {
    private val eqFilters = Array(5) { BiquadFilter() }
    private val lowPass = BiquadFilter()
    private val reverb = ReverbEffect(sampleRate)

    private val bandFreqs = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)
    private val q = 1f / sqrt(2f)
    private var lastBandGains = FloatArray(5)
    private var lastVolReduction = 0f

    fun process(buffer: FloatArray, bandGains: FloatArray, reverbMix: Float, volumeReductionDb: Float) {
        for (i in 0 until 5) {
            val g = bandGains.getOrElse(i) { 0f }
            if (g != lastBandGains.getOrElse(i) { 0f }) {
                eqFilters[i].setPeaking(bandFreqs[i], q, g, sampleRate)
            }
            if (g != 0f) eqFilters[i].process(buffer)
        }
        lastBandGains = bandGains.copyOf()

        if (volumeReductionDb < 0f) {
            if (volumeReductionDb != lastVolReduction) {
                lowPass.setLowPass(350f, 0.5f, sampleRate)
                lastVolReduction = volumeReductionDb
            }
            lowPass.process(buffer)
        } else if (lastVolReduction < 0f) {
            lastVolReduction = 0f
        }

        reverb.process(buffer, reverbMix.coerceIn(0f, 1f))

        val amp = 10f.pow(volumeReductionDb / 20f)
        if (amp != 1f) {
            for (i in buffer.indices) buffer[i] *= amp
        }
    }
}

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
    private var prevVolumeAmp = 1f

    fun process(buffer: FloatArray, channels: Int, bandGains: FloatArray, reverbMix: Float, volumeReductionDb: Float) {
        if (channels > 1) {
            processPerChannel(buffer, channels, bandGains, reverbMix, volumeReductionDb)
        } else {
            processMono(buffer, bandGains, reverbMix, volumeReductionDb)
        }
        lastBandGains = bandGains.copyOf()
    }

    private fun processMono(buffer: FloatArray, bandGains: FloatArray, reverbMix: Float, volumeReductionDb: Float) {
        applyEQ(buffer, bandGains)
        applyLowPass(buffer, volumeReductionDb)
        applyReverb(buffer, reverbMix)
        applyVolumeRamped(buffer, volumeReductionDb, prevVolumeAmp)
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun processPerChannel(buffer: FloatArray, channels: Int, bandGains: FloatArray, reverbMix: Float, volumeReductionDb: Float) {
        val frameSize = channels
        val frames = buffer.size / frameSize
        val chStartAmp = prevVolumeAmp
        for (ch in 0 until channels) {
            val chBuf = FloatArray(frames)
            var idx = ch
            for (f in 0 until frames) {
                chBuf[f] = buffer[idx]
                idx += frameSize
            }
            val savedGains = lastBandGains.copyOf()
            val savedVol = lastVolReduction
            applyEQ(chBuf, bandGains)
            applyLowPass(chBuf, volumeReductionDb)
            applyReverb(chBuf, reverbMix)
            applyVolumeRamped(chBuf, volumeReductionDb, chStartAmp)
            idx = ch
            for (f in 0 until frames) {
                buffer[idx] = chBuf[f]
                idx += frameSize
            }
            lastBandGains = savedGains.copyOf()
            lastVolReduction = savedVol
        }
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun applyEQ(buffer: FloatArray, bandGains: FloatArray) {
        for (i in 0 until 5) {
            val g = bandGains.getOrElse(i) { 0f }
            if (g != lastBandGains.getOrElse(i) { 0f }) {
                eqFilters[i].setPeaking(bandFreqs[i], q, g, sampleRate)
            }
            if (g != 0f) eqFilters[i].process(buffer)
        }
    }

    private fun applyLowPass(buffer: FloatArray, volumeReductionDb: Float) {
        if (volumeReductionDb < 0f) {
            if (volumeReductionDb != lastVolReduction) {
                lowPass.setLowPass(350f, 0.5f, sampleRate)
                lastVolReduction = volumeReductionDb
            }
            lowPass.process(buffer)
        } else if (lastVolReduction < 0f) {
            lastVolReduction = 0f
        }
    }

    private fun applyReverb(buffer: FloatArray, reverbMix: Float) {
        val bounded = reverbMix.coerceIn(0f, 1f)
        if (bounded > 0f) reverb.process(buffer, bounded)
    }

    private fun applyVolumeRamped(buffer: FloatArray, volumeReductionDb: Float, startAmp: Float) {
        val targetAmp = 10f.pow(volumeReductionDb / 20f)
        if (targetAmp == startAmp) {
            if (targetAmp != 1f) {
                for (i in buffer.indices) buffer[i] *= targetAmp
            }
            return
        }
        val step = (targetAmp - startAmp) / buffer.size
        var amp = startAmp
        for (i in buffer.indices) {
            buffer[i] *= amp
            amp += step
        }
    }

    fun reset() {
        for (f in eqFilters) f.resetHistory()
        lowPass.resetHistory()
        lastBandGains.fill(0f)
        lastVolReduction = 0f
        prevVolumeAmp = 1f
    }
}

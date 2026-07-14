package com.motionsound.drive

import kotlin.math.pow
import kotlin.math.sqrt

class DspProcessor(private val sampleRate: Float) {
    private val eqFilters = Array(5) { Array(2) { BiquadFilter() } }
    private val lowPassFilters = Array(2) { BiquadFilter() }

    private val bandFreqs = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)
    private val q = 1f / sqrt(2f)
    private var lastBandGains = FloatArray(5)

    init {
        for (i in 0 until 5) {
            for (ch in 0 until 2) {
                eqFilters[i][ch].setPeaking(bandFreqs[i], q, 0f, sampleRate)
            }
        }
    }
    private var prevVolumeAmp = 1f

    fun process(buffer: FloatArray, channels: Int, bandGains: FloatArray, volumeReductionDb: Float, debug: DspDebugConfig = DspDebugConfig()) {
        if (debug.bypassAll) return

        if (debug.enableEQ) {
            val maxCh = kotlin.math.min(channels, 2)
            for (i in 0 until 5) {
                val g = bandGains.getOrElse(i) { 0f }
                if (kotlin.math.abs(g - lastBandGains.getOrElse(i) { 0f }) > 0.2f) {
                    for (ch in 0 until maxCh) {
                        eqFilters[i][ch].setPeaking(bandFreqs[i], q, g, sampleRate)
                    }
                }
            }
            lastBandGains = bandGains.copyOf()
        }

        if (channels > 1) {
            processPerChannel(buffer, channels, volumeReductionDb, debug)
        } else {
            processMono(buffer, volumeReductionDb, debug)
        }
    }

    private fun processMono(buffer: FloatArray, volumeReductionDb: Float, debug: DspDebugConfig) {
        if (debug.enableEQ) applyEQ(buffer, 0)
        if (debug.enableLowPass) applyLowPass(buffer, 0, volumeReductionDb)
        applyVolumeRamped(buffer, volumeReductionDb, prevVolumeAmp, debug)
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun processPerChannel(buffer: FloatArray, channels: Int, volumeReductionDb: Float, debug: DspDebugConfig) {
        val frameSize = channels
        val frames = buffer.size / frameSize
        for (ch in 0 until channels) {
            val chBuf = FloatArray(frames)
            var idx = ch
            for (f in 0 until frames) {
                chBuf[f] = buffer[idx]
                idx += frameSize
            }
            if (debug.enableEQ) applyEQ(chBuf, ch)
            if (debug.enableLowPass) applyLowPass(chBuf, ch, volumeReductionDb)
            applyVolumeRamped(chBuf, volumeReductionDb, prevVolumeAmp, debug)
            idx = ch
            for (f in 0 until frames) {
                buffer[idx] = chBuf[f]
                idx += frameSize
            }
        }
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun applyEQ(buffer: FloatArray, ch: Int) {
        for (i in 0 until 5) {
            eqFilters[i][ch].process(buffer)
        }
    }

    private fun applyLowPass(buffer: FloatArray, ch: Int, volDb: Float) {
        val depth = (-volDb / 14f).coerceIn(0f, 1f)
        if (depth > 0.01f) {
            val cutoff = 18000f - 17650f * depth
            lowPassFilters[ch].setLowPass(cutoff, 0.5f, sampleRate)
            lowPassFilters[ch].process(buffer)
        }
    }

    private fun applyVolumeRamped(buffer: FloatArray, volumeReductionDb: Float, startAmp: Float, debug: DspDebugConfig) {
        val targetAmp = 10f.pow(volumeReductionDb / 20f)
        if (!debug.enableVolumeDuck) return
        if (!debug.enableVolumeRamp) {
            if (targetAmp != 1f) {
                for (i in buffer.indices) buffer[i] *= targetAmp
            }
            return
        }
        if (targetAmp == startAmp) {
            if (targetAmp != 1f) {
                for (i in buffer.indices) buffer[i] *= targetAmp
            }
            return
        }
        val ratio = targetAmp / startAmp
        val step = ratio.pow(1f / buffer.size)
        var amp = startAmp
        for (i in buffer.indices) {
            buffer[i] *= amp
            amp *= step
        }
    }

    fun reset() {
        for (band in eqFilters) for (f in band) f.resetHistory()
        for (f in lowPassFilters) f.resetHistory()
        lastBandGains.fill(0f)
        prevVolumeAmp = 1f
    }
}

package com.motionsound.drive

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DspProcessor(private val sampleRate: Float) {
    private class SchroederReverb(sr: Float) {
        private data class Line(val buf: FloatArray, var ptr: Int, val size: Int, val fb: Float)
        private val combs: List<Line>
        private val allpass: List<Line>

        init {
            val combDelaySamples = intArrayOf(1310, 1636, 1813, 1927)
            val apDelaySamples = intArrayOf(221, 75)
            combs = combDelaySamples.map { Line(FloatArray(it), 0, it, DrivingConfig.REVERB_COMB_FEEDBACK) }
            allpass = apDelaySamples.map { Line(FloatArray(it), 0, it, DrivingConfig.REVERB_ALLPASS_GAIN) }
        }

        fun process(input: Float): Float {
            var wet = 0f
            for (c in combs) {
                val r = c.buf[c.ptr]
                c.buf[c.ptr] = input + r * c.fb
                c.ptr = (c.ptr + 1) % c.size
                wet += r
            }
            wet /= combs.size
            for (a in allpass) {
                val r = a.buf[a.ptr]
                a.buf[a.ptr] = wet + r * a.fb
                wet = -a.fb * wet + r
                a.ptr = (a.ptr + 1) % a.size
            }
            return wet
        }

        fun reset() {
            for (c in combs) { c.buf.fill(0f); c.ptr = 0 }
            for (a in allpass) { a.buf.fill(0f); a.ptr = 0 }
        }
    }

    private val eqFilters = Array(5) { Array(2) { BiquadFilter() } }
    private val lowPassFilters = Array(2) { BiquadFilter() }
    private val reverbL = SchroederReverb(sampleRate)
    private val reverbR = SchroederReverb(sampleRate)
    private var tremoloPhase = 0f

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

    fun process(buffer: FloatArray, channels: Int, bandGains: FloatArray, volumeReductionDb: Float,
                lowpassDepth: Float = 0f, debug: DspDebugConfig = DspDebugConfig(),
                reverbWet: Float = 0f, tremoloDepth: Float = 0f) {
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
            processPerChannel(buffer, channels, volumeReductionDb, lowpassDepth, debug, reverbWet, tremoloDepth)
        } else {
            processMono(buffer, volumeReductionDb, lowpassDepth, debug, reverbWet, tremoloDepth)
        }
    }

    private fun processMono(buffer: FloatArray, volumeReductionDb: Float, lowpassDepth: Float, debug: DspDebugConfig, reverbWet: Float = 0f, tremoloDepth: Float = 0f) {
        if (debug.enableEQ) applyEQ(buffer, 0)
        if (debug.enableLowPass) applyLowPass(buffer, 0, lowpassDepth)
        if (debug.enableReverb) applyReverb(buffer, 0, reverbWet)
        if (debug.enableTremolo) applyTremolo(buffer, tremoloDepth)
        applyVolumeRamped(buffer, volumeReductionDb, prevVolumeAmp, debug)
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun processPerChannel(buffer: FloatArray, channels: Int, volumeReductionDb: Float, lowpassDepth: Float, debug: DspDebugConfig, reverbWet: Float = 0f, tremoloDepth: Float = 0f) {
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
            if (debug.enableLowPass) applyLowPass(chBuf, ch, lowpassDepth)
            if (debug.enableReverb) applyReverb(chBuf, ch, reverbWet)
            if (debug.enableTremolo) applyTremolo(chBuf, tremoloDepth)
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

    private fun applyLowPass(buffer: FloatArray, ch: Int, depth: Float) {
        if (depth > 0.01f) {
            val cutoff = 18000f - 17650f * depth.coerceIn(0f, 1f)
            lowPassFilters[ch].setLowPass(cutoff, 0.5f, sampleRate)
            lowPassFilters[ch].process(buffer)
        }
    }

    private fun applyReverb(buffer: FloatArray, ch: Int, wet: Float) {
        if (wet < 0.001f) return
        val reverb = if (ch == 0) reverbL else reverbR
        val dry = 1f - wet
        for (i in buffer.indices) {
            val rev = reverb.process(buffer[i])
            buffer[i] = buffer[i] * dry + rev * wet
        }
    }

    private fun applyTremolo(buffer: FloatArray, depth: Float) {
        if (depth < 0.001f) return
        val rate = DrivingConfig.TREMOLO_RATE_HZ
        val phaseDelta = rate / sampleRate
        for (i in buffer.indices) {
            val lfo = 0.5f + 0.5f * sin(2f * PI * tremoloPhase)
            tremoloPhase += phaseDelta
            if (tremoloPhase >= 1f) tremoloPhase -= 1f
            buffer[i] *= (1f - depth * lfo).toFloat()
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
        reverbL.reset()
        reverbR.reset()
        tremoloPhase = 0f
    }
}

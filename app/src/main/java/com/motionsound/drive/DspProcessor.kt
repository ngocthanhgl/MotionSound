package com.motionsound.drive

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DspProcessor(private val sampleRate: Float) {
    private class SchroederReverb(sr: Float) {
        private val cBuf0 = FloatArray(1310); private var cPtr0 = 0
        private val cBuf1 = FloatArray(1636); private var cPtr1 = 0
        private val cBuf2 = FloatArray(1813); private var cPtr2 = 0
        private val cBuf3 = FloatArray(1927); private var cPtr3 = 0
        private val aBuf0 = FloatArray(221);  private var aPtr0 = 0
        private val aBuf1 = FloatArray(75);   private var aPtr1 = 0

        fun process(input: Float): Float {
            val fb = DrivingConfig.REVERB_COMB_FEEDBACK
            val ag = DrivingConfig.REVERB_ALLPASS_GAIN

            var r: Float
            r = cBuf0[cPtr0]; cBuf0[cPtr0] = input + r * fb; cPtr0 = (cPtr0 + 1) % 1310; var wet = r
            r = cBuf1[cPtr1]; cBuf1[cPtr1] = input + r * fb; cPtr1 = (cPtr1 + 1) % 1636; wet += r
            r = cBuf2[cPtr2]; cBuf2[cPtr2] = input + r * fb; cPtr2 = (cPtr2 + 1) % 1813; wet += r
            r = cBuf3[cPtr3]; cBuf3[cPtr3] = input + r * fb; cPtr3 = (cPtr3 + 1) % 1927; wet += r
            wet *= 0.25f

            r = aBuf0[aPtr0]; aBuf0[aPtr0] = wet + r * ag; wet = -ag * wet + r; aPtr0 = (aPtr0 + 1) % 221
            r = aBuf1[aPtr1]; aBuf1[aPtr1] = wet + r * ag; wet = -ag * wet + r; aPtr1 = (aPtr1 + 1) % 75

            return wet
        }

        fun reset() {
            cBuf0.fill(0f); cPtr0 = 0; cBuf1.fill(0f); cPtr1 = 0
            cBuf2.fill(0f); cPtr2 = 0; cBuf3.fill(0f); cPtr3 = 0
            aBuf0.fill(0f); aPtr0 = 0; aBuf1.fill(0f); aPtr1 = 0
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

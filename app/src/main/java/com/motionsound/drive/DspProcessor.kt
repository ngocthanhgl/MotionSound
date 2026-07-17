package com.motionsound.drive

import kotlin.math.pow
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
    private val reverbL = SchroederReverb(sampleRate)
    private val reverbR = SchroederReverb(sampleRate)

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
                debug: DspDebugConfig = DspDebugConfig(),
                reverbWet: Float = 0f, stereoPanOffset: Float = 0f, stereoWidth: Float = 1f) {
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
            processPerChannel(buffer, channels, volumeReductionDb, debug, reverbWet, stereoPanOffset, stereoWidth)
        } else {
            processMono(buffer, volumeReductionDb, debug, reverbWet)
        }
    }

    private fun processMono(buffer: FloatArray, volumeReductionDb: Float, debug: DspDebugConfig, reverbWet: Float = 0f) {
        if (debug.enableEQ) applyEQ(buffer, 0)
        if (debug.enableReverb) applyReverb(buffer, 0, reverbWet)
        applyVolumeRamped(buffer, volumeReductionDb, prevVolumeAmp, debug)
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun processPerChannel(buffer: FloatArray, channels: Int, volumeReductionDb: Float, debug: DspDebugConfig, reverbWet: Float = 0f, stereoPanOffset: Float = 0f, stereoWidth: Float = 1f) {
        val frameSize = channels
        val frames = buffer.size / frameSize
        val maxCh = kotlin.math.min(channels, 2)

        for (ch in 0 until maxCh) {
            val chBuf = FloatArray(frames)
            var idx = ch
            for (f in 0 until frames) {
                chBuf[f] = buffer[idx]
                idx += frameSize
            }
            if (debug.enableEQ) applyEQ(chBuf, ch)
            if (debug.enableReverb) applyReverb(chBuf, ch, reverbWet)
            idx = ch
            for (f in 0 until frames) {
                buffer[idx] = chBuf[f]
                idx += frameSize
            }
        }

        if (maxCh >= 2) {
            if (debug.enablePanning && kotlin.math.abs(stereoPanOffset) > 0.01f) {
                applyStereoPan(buffer, channels, stereoPanOffset)
            }
            if (debug.enableStereoWidth && kotlin.math.abs(stereoWidth - 1f) > 0.01f) {
                applyStereoWidth(buffer, channels, stereoWidth)
            }
        }

        applyVolumeRamped(buffer, volumeReductionDb, prevVolumeAmp, debug)
        prevVolumeAmp = 10f.pow(volumeReductionDb / 20f)
    }

    private fun applyEQ(buffer: FloatArray, ch: Int) {
        for (i in 0 until 5) {
            eqFilters[i][ch].process(buffer)
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

    private fun applyStereoPan(buffer: FloatArray, channels: Int, panOffset: Float) {
        val leftGain = (1f - maxOf(0f, panOffset) * DrivingConfig.CORNER_PAN_DEPTH).coerceIn(0.3f, 1f)
        val rightGain = (1f - maxOf(0f, -panOffset) * DrivingConfig.CORNER_PAN_DEPTH).coerceIn(0.3f, 1f)
        for (i in buffer.indices step channels) {
            buffer[i] *= leftGain
            if (channels > 1) buffer[i + 1] *= rightGain
        }
    }

    private fun applyStereoWidth(buffer: FloatArray, channels: Int, width: Float) {
        if (channels < 2) return
        for (i in buffer.indices step channels) {
            val l = buffer[i]
            val r = buffer[i + 1]
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            buffer[i] = mid + side
            buffer[i + 1] = mid - side
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
        lastBandGains.fill(0f)
        prevVolumeAmp = 1f
        reverbL.reset()
        reverbR.reset()
    }
}

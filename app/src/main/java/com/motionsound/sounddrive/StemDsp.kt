package com.motionsound.sounddrive

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class BiquadFilter {
    private var lx1 = 0f; private var lx2 = 0f
    private var ly1 = 0f; private var ly2 = 0f
    private var rx1 = 0f; private var rx2 = 0f
    private var ry1 = 0f; private var ry2 = 0f

    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f

    private var lastCutoff = -1f
    private var lastResonance = -1f

    fun lowPass(cutoffNorm: Float, resonance: Float) {
        if (cutoffNorm >= 0.99f) {
            if (lastCutoff >= 0.99f) return
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            lastCutoff = cutoffNorm; lastResonance = resonance
            return
        }
        if (cutoffNorm == lastCutoff && resonance == lastResonance) return
        lastCutoff = cutoffNorm; lastResonance = resonance
        val w0 = PI.toFloat() * cutoffNorm.coerceIn(0f, 0.99f)
        val alpha = sin(w0) / (2f * resonance.coerceIn(0.1f, 1f))
        val cosW0 = cos(w0)
        b0 = (1f - cosW0) / 2f
        b1 = 1f - cosW0
        b2 = b0
        a1 = -2f * cosW0
        a2 = 1f - alpha
        val norm = 1f + alpha
        b0 /= norm; b1 /= norm; b2 /= norm
        a1 /= norm; a2 /= norm
    }

    fun highPass(cutoffNorm: Float, resonance: Float) {
        if (cutoffNorm <= 0.001f) {
            if (lastCutoff <= 0.001f) return
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            lastCutoff = cutoffNorm; lastResonance = resonance
            return
        }
        if (cutoffNorm == lastCutoff && resonance == lastResonance) return
        lastCutoff = cutoffNorm; lastResonance = resonance
        val w0 = PI.toFloat() * cutoffNorm.coerceIn(0f, 0.99f)
        val alpha = sin(w0) / (2f * resonance.coerceIn(0.1f, 1f))
        val cosW0 = cos(w0)
        b0 = (1f + cosW0) / 2f
        b1 = -(1f + cosW0)
        b2 = b0
        a1 = -2f * cosW0
        a2 = 1f - alpha
        val norm = 1f + alpha
        b0 /= norm; b1 /= norm; b2 /= norm
        a1 /= norm; a2 /= norm
    }

    fun processLeft(sample: Float): Float {
        if (b0.isNaN() || b0.isInfinite()) {
            reset(); lastCutoff = -1f; lastResonance = -1f
        }
        val out = b0 * sample + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
        lx2 = lx1; lx1 = sample; ly2 = ly1; ly1 = out
        return out
    }

    fun processRight(sample: Float): Float {
        if (b0.isNaN() || b0.isInfinite()) {
            reset(); lastCutoff = -1f; lastResonance = -1f
        }
        val out = b0 * sample + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
        rx2 = rx1; rx1 = sample; ry2 = ry1; ry1 = out
        return out
    }

    fun reset() {
        lx1 = 0f; lx2 = 0f; ly1 = 0f; ly2 = 0f
        rx1 = 0f; rx2 = 0f; ry1 = 0f; ry2 = 0f
    }
}

class StemFxChain {
    val filter = BiquadFilter()
    var pan: Float = 0f

    fun accumulate(
        stem: FloatBuffer,
        startFrame: Int,
        count: Int,
        vol: Float,
        out: FloatArray
    ) {
        val angle = (pan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
        val gainL = cos(angle)
        val gainR = sin(angle)

        for (f in 0 until count) {
            val stemIdx = (startFrame + f) * 2
            val outIdx = f * 2
            val l = filter.processLeft(stem.get(stemIdx))
            val r = filter.processRight(stem.get(stemIdx + 1))
            out[outIdx] += l * gainL * vol
            out[outIdx + 1] += r * gainR * vol
        }
    }
}

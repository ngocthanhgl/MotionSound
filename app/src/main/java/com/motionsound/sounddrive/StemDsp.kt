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
    private var tb0 = 1f; private var tb1 = 0f; private var tb2 = 0f
    private var ta1 = 0f; private var ta2 = 0f

    private var lastCutoff = -1f
    private var lastResonance = -1f

    private val smoothCoef = 0.03f

    fun lowPass(cutoffNorm: Float, resonance: Float) {
        if (cutoffNorm >= 0.99f) {
            if (lastCutoff >= 0.99f) return
            tb0 = 1f; tb1 = 0f; tb2 = 0f; ta1 = 0f; ta2 = 0f
            lastCutoff = cutoffNorm; lastResonance = resonance
            return
        }
        if (cutoffNorm == lastCutoff && resonance == lastResonance) return
        lastCutoff = cutoffNorm; lastResonance = resonance
        val w0 = PI.toFloat() * cutoffNorm.coerceIn(0f, 0.99f)
        val alpha = sin(w0) / (2f * resonance.coerceIn(0.1f, 1f))
        val cosW0 = cos(w0)
        tb0 = (1f - cosW0) / 2f
        tb1 = 1f - cosW0
        tb2 = tb0
        ta1 = -2f * cosW0
        ta2 = 1f - alpha
        val norm = 1f + alpha
        tb0 /= norm; tb1 /= norm; tb2 /= norm
        ta1 /= norm; ta2 /= norm
    }

    fun highPass(cutoffNorm: Float, resonance: Float) {
        if (cutoffNorm <= 0.001f) {
            if (lastCutoff <= 0.001f) return
            tb0 = 1f; tb1 = 0f; tb2 = 0f; ta1 = 0f; ta2 = 0f
            lastCutoff = cutoffNorm; lastResonance = resonance
            return
        }
        if (cutoffNorm == lastCutoff && resonance == lastResonance) return
        lastCutoff = cutoffNorm; lastResonance = resonance
        val w0 = PI.toFloat() * cutoffNorm.coerceIn(0f, 0.99f)
        val alpha = sin(w0) / (2f * resonance.coerceIn(0.1f, 1f))
        val cosW0 = cos(w0)
        tb0 = (1f + cosW0) / 2f
        tb1 = -(1f + cosW0)
        tb2 = tb0
        ta1 = -2f * cosW0
        ta2 = 1f - alpha
        val norm = 1f + alpha
        tb0 /= norm; tb1 /= norm; tb2 /= norm
        ta1 /= norm; ta2 /= norm
    }

    fun processLeft(sample: Float): Float {
        if (b0.isNaN() || b0.isInfinite()) {
            reset(); lastCutoff = -1f; lastResonance = -1f
        }
        b0 += (tb0 - b0) * smoothCoef
        b1 += (tb1 - b1) * smoothCoef
        b2 += (tb2 - b2) * smoothCoef
        a1 += (ta1 - a1) * smoothCoef
        a2 += (ta2 - a2) * smoothCoef
        val out = b0 * sample + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
        lx2 = lx1; lx1 = sample; ly2 = ly1; ly1 = out
        return out
    }

    fun processRight(sample: Float): Float {
        if (b0.isNaN() || b0.isInfinite()) {
            reset(); lastCutoff = -1f; lastResonance = -1f
        }
        b0 += (tb0 - b0) * smoothCoef
        b1 += (tb1 - b1) * smoothCoef
        b2 += (tb2 - b2) * smoothCoef
        a1 += (ta1 - a1) * smoothCoef
        a2 += (ta2 - a2) * smoothCoef
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

    private var volCur = 1f
    private var panCur = 0f
    private val smoothCoef = 0.008f

    fun smoothedGains(targetVol: Float, g: FloatArray) {
        volCur += (targetVol.coerceIn(0f, 1f) - volCur) * smoothCoef
        panCur += (pan.coerceIn(-1f, 1f) - panCur) * smoothCoef
        val angle = (panCur + 1f) * 0.25f * PI.toFloat()
        g[0] = cos(angle) * volCur
        g[1] = sin(angle) * volCur
    }

    fun accumulate(
        stem: FloatBuffer,
        startFrame: Int,
        count: Int,
        vol: Float,
        out: FloatArray
    ) {
        val g = FloatArray(2)

        for (f in 0 until count) {
            val stemIdx = (startFrame + f) * 2
            val outIdx = f * 2
            smoothedGains(vol, g)
            val l = filter.processLeft(stem.get(stemIdx))
            val r = filter.processRight(stem.get(stemIdx + 1))
            out[outIdx] += l * g[0]
            out[outIdx + 1] += r * g[1]
        }
    }
}

class Reverb {
    private val combDelays = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356)
    private val allpassDelays = intArrayOf(225, 556, 441, 341)
    private val combBuffersL = Array(combDelays.size) { FloatArray(combDelays[it]) }
    private val combBuffersR = Array(combDelays.size) { FloatArray(combDelays[it]) }
    private val allpassBuffersL = Array(allpassDelays.size) { FloatArray(allpassDelays[it]) }
    private val allpassBuffersR = Array(allpassDelays.size) { FloatArray(allpassDelays[it]) }
    private val combIndexL = IntArray(combDelays.size)
    private val combIndexR = IntArray(combDelays.size)
    private val allpassIndexL = IntArray(allpassDelays.size)
    private val allpassIndexR = IntArray(allpassDelays.size)
    private val combDampL = FloatArray(combDelays.size)
    private val combDampR = FloatArray(combDelays.size)

    private var combFeedback = 0.6f
    private var allpassFeedback = 0.5f
    private val combDamping = 0.35f

    private var lastSize = -1f
    private var lastDecay = -1f

    fun configure(size: Float, decay: Float) {
        if (size == lastSize && decay == lastDecay) return
        lastSize = size.coerceIn(0f, 1f)
        lastDecay = decay.coerceIn(0f, 1f)
        combFeedback = (0.5f + lastSize * 0.15f + lastDecay * 0.25f).coerceIn(0.45f, 0.82f)
        allpassFeedback = 0.5f
    }

    fun reset() {
        for (b in combBuffersL) b.fill(0f)
        for (b in combBuffersR) b.fill(0f)
        for (b in allpassBuffersL) b.fill(0f)
        for (b in allpassBuffersR) b.fill(0f)
        combDampL.fill(0f)
        combDampR.fill(0f)
    }

    fun processLeft(input: Float): Float =
        process(input, combBuffersL, combIndexL, combDampL, allpassBuffersL, allpassIndexL)

    fun processRight(input: Float): Float =
        process(input, combBuffersR, combIndexR, combDampR, allpassBuffersR, allpassIndexR)

    private fun process(
        input: Float,
        combs: Array<FloatArray>,
        cIdx: IntArray,
        damp: FloatArray,
        allpasses: Array<FloatArray>,
        aIdx: IntArray
    ): Float {
        var out = 0f
        for (c in combs.indices) {
            val buf = combs[c]
            val idx = cIdx[c]
            val delayed = buf[idx]
            val damped = damp[c] + (delayed - damp[c]) * (1f - combDamping)
            damp[c] = damped
            buf[idx] = input + damped * combFeedback
            cIdx[c] = (idx + 1) % buf.size
            out += delayed
        }
        out *= 1f / combs.size.toFloat()

        for (a in allpasses.indices) {
            val buf = allpasses[a]
            val idx = aIdx[a]
            val delayed = buf[idx]
            buf[idx] = out + delayed * allpassFeedback
            out = delayed - buf[idx] * allpassFeedback
            aIdx[a] = (idx + 1) % buf.size
        }
        return out
    }
}

class Tremolo {
    private var phase = 0f
    private var lastRate = -1f
    private var lastDepth = -1f

    private var rateHz = 4f
    private var depth = 0f

    fun configure(rateHz: Float, depth: Float) {
        if (rateHz == lastRate && depth == lastDepth) return
        this.rateHz = rateHz.coerceIn(0.1f, 30f)
        this.depth = depth.coerceIn(0f, 1f)
        lastRate = rateHz
        lastDepth = depth
    }

    fun process(input: Float): Float {
        phase += rateHz / 44100f
        if (phase > 1f) phase -= 1f
        val mod = 1f - depth * (0.5f + 0.5f * cos(2f * PI.toFloat() * phase))
        return input * mod
    }

    fun reset() {
        phase = 0f
    }
}

class Echo {
    private val delayL = 11025
    private val delayR = 12700
    private val bufL = FloatArray(delayL)
    private val bufR = FloatArray(delayR)
    private var idxL = 0
    private var idxR = 0
    private var dampL = 0f
    private var dampR = 0f
    private val feedback = 0.45f
    private val damping = 0.3f

    fun processLeft(input: Float): Float {
        val delayed = bufL[idxL]
        val tail = dampL * damping + delayed * (1f - damping)
        dampL = tail
        bufL[idxL] = input + tail * feedback
        idxL = (idxL + 1) % delayL
        return tail
    }

    fun processRight(input: Float): Float {
        val delayed = bufR[idxR]
        val tail = dampR * damping + delayed * (1f - damping)
        dampR = tail
        bufR[idxR] = input + tail * feedback
        idxR = (idxR + 1) % delayR
        return tail
    }

    fun reset() {
        bufL.fill(0f)
        bufR.fill(0f)
        dampL = 0f
        dampR = 0f
    }
}

class Warp {
    private val maxDelaySamples = 882
    private val bufL = FloatArray(maxDelaySamples + 8)
    private val bufR = FloatArray(maxDelaySamples + 8)
    private var idxL = 0
    private var idxR = 0
    private var phase = 0f
    private var lastRate = -1f
    private var lastDepth = -1f

    private var rateHz = 0.5f
    private var depthSamples = 0f
    private var targetDepthSamples = 0f

    private fun smoothDepth() {
        depthSamples += (targetDepthSamples - depthSamples) * 0.02f
    }

    fun configure(rate: Float, depthMs: Float) {
        val r = rate.coerceIn(0.05f, 8f)
        val d = depthMs.coerceIn(0f, 6f)
        if (r == lastRate && d == lastDepth) return
        rateHz = r
        targetDepthSamples = (d / 1000f * 44100f).coerceAtMost(maxDelaySamples.toFloat())
        lastRate = r
        lastDepth = d
    }

    private fun advancePhase() {
        phase += rateHz / 44100f
        if (phase > 1f) phase -= 1f
    }

    fun processLeft(input: Float): Float {
        advancePhase()
        smoothDepth()
        val lfo = 0.5f + 0.5f * cos(2f * PI.toFloat() * phase)
        val delay = (depthSamples * lfo).toInt()
        val read = (idxL - delay - 1 + bufL.size) % bufL.size
        val delayed = bufL[read]
        bufL[idxL] = input
        idxL = (idxL + 1) % bufL.size
        return input + delayed * 0.5f
    }

    fun processRight(input: Float): Float {
        val lfo = 0.5f + 0.5f * cos(2f * PI.toFloat() * (phase + 0.5f))
        val delay = (depthSamples * lfo).toInt()
        val read = (idxR - delay - 1 + bufR.size) % bufR.size
        val delayed = bufR[read]
        bufR[idxR] = input
        idxR = (idxR + 1) % bufR.size
        return input + delayed * 0.5f
    }

    fun reset() {
        bufL.fill(0f)
        bufR.fill(0f)
        phase = 0f
    }
}

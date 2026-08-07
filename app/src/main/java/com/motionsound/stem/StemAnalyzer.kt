package com.motionsound.stem

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class StemAnalysis(
    val beatFrames: IntArray,
    val sectionEnergy: FloatArray,
    val sectionBlockFrames: Int
)

object StemAnalyzer {
    private const val BLOCK = 2048
    private const val MIN_BEAT_SPACING = 22050

    fun analyze(stems: StemResult): StemAnalysis {
        val fc = stems.frameCount
        val nBlocks = max(1, fc / BLOCK)
        val rms = FloatArray(nBlocks)

        for (b in 0 until nBlocks) {
            val start = b * BLOCK
            val end = min(start + BLOCK, fc)
            var sum = 0.0
            var count = 0
            var f = start
            while (f < end) {
                val idx = f * 2
                val d = stems.drums.get(idx) + stems.drums.get(idx + 1)
                val b2 = stems.bass.get(idx) + stems.bass.get(idx + 1)
                sum += (d * d + b2 * b2) * 0.5
                count++
                f += 4
            }
            rms[b] = if (count > 0) sqrt((sum / count).toFloat()) else 0f
        }

        var mean = 0f
        for (v in rms) mean += v
        mean /= nBlocks
        var variance = 0.0
        for (v in rms) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt(variance / nBlocks).toFloat()
        val threshold = mean + 1.1f * std

        val beats = ArrayList<Int>()
        var lastBeat = -MIN_BEAT_SPACING
        for (b in 1 until nBlocks - 1) {
            if (rms[b] > threshold && rms[b] >= rms[b - 1] && rms[b] >= rms[b + 1]) {
                val frame = b * BLOCK + BLOCK / 2
                if (frame - lastBeat >= MIN_BEAT_SPACING) {
                    beats.add(frame)
                    lastBeat = frame
                }
            }
        }

        val sectionBlockFrames = 16 * BLOCK
        val nSections = max(1, (fc + sectionBlockFrames - 1) / sectionBlockFrames)
        val sectionEnergy = FloatArray(nSections)
        var maxSection = 0f
        for (s in 0 until nSections) {
            val start = s * sectionBlockFrames
            val end = min(start + sectionBlockFrames, fc)
            val b0 = start / BLOCK
            val b1 = (end + BLOCK - 1) / BLOCK
            var sum = 0f
            var cnt = 0
            for (b in b0 until min(b1, nBlocks)) {
                sum += rms[b]
                cnt++
            }
            sectionEnergy[s] = if (cnt > 0) sum / cnt else 0f
            if (sectionEnergy[s] > maxSection) maxSection = sectionEnergy[s]
        }
        if (maxSection > 0f) {
            for (s in sectionEnergy.indices) {
                sectionEnergy[s] = (sectionEnergy[s] / maxSection).coerceIn(0f, 1f)
            }
        }

        return StemAnalysis(beats.toIntArray(), sectionEnergy, sectionBlockFrames)
    }
}

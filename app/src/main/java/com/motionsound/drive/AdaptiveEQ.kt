package com.motionsound.drive

import android.media.audiofx.Equalizer
import kotlin.math.abs

data class EQTarget(val bandGains: FloatArray)

class AdaptiveEQ {

    private val equalizer: Equalizer = Equalizer(0, 0)
    private val bandCount: Int = equalizer.numberOfBands.toInt()
    private val bandCenters: IntArray

    init {
        equalizer.enabled = true
        bandCenters = IntArray(bandCount) { i ->
            val range = equalizer.getBandFreqRange(i.toShort())
            (range[0] + range[1]) / 2
        }
    }

    fun applyTarget(target: EQTarget) {
        for (i in 0 until bandCount.coerceAtMost(target.bandGains.size)) {
            val millibels = (target.bandGains[i] * 100).toInt().toShort()
                .coerceIn(-1500, 1500)
            equalizer.setBandLevel(i.toShort(), millibels)
        }
    }

    fun computeTarget(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        speedNorm: Float,
        depthWeight: Float,
        neutralBias: FloatArray?,
        volumeReductionDb: Float = 0f
    ): EQTarget {
        val gains = FloatArray(bandCount) { 0f }
        val usedDepth = depthWeight.coerceIn(0f, 1f)

        for (i in 0 until bandCount) {
            val centerHz = bandCenters[i]
            var g = 0f

            if (centerHz in 30..250) {
                g += accelIntensity * DrivingConfig.MAX_BOOST_DB * usedDepth
            }

            if (centerHz in 800..5000) {
                g -= brakeIntensity * abs(DrivingConfig.MAX_CUT_DB) * usedDepth * 0.7f
            }

            if (centerHz in 30..150 || centerHz > 8000) {
                g += speedNorm * 1.5f * usedDepth
            }

            if (centerHz > 8000 && cornerIntensity > 0.3f) {
                g -= cornerIntensity * 0.5f * usedDepth
            }

            neutralBias?.let { bias ->
                if (i < bias.size) g += bias[i]
            }

            g = g.coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
            gains[i] = g
        }

        val volDb = volumeReductionDb
        if (volDb != 0f) {
            for (i in 0 until bandCount) {
                gains[i] = (gains[i] + volDb)
                    .coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
                if (volDb < 0f) {
                    val centerHz = bandCenters[i]
                    if (centerHz in DrivingConfig.VOCAL_LOW_HZ..DrivingConfig.VOCAL_HIGH_HZ) {
                        gains[i] = (gains[i] + DrivingConfig.VOCAL_INSIDE_BOOST_DB)
                            .coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
                    } else {
                        gains[i] = (gains[i] + DrivingConfig.VOCAL_OUTSIDE_CUT_DB)
                            .coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
                    }
                }
            }
        }

        val maxGain = gains.maxOrNull() ?: 0f
        if (maxGain > DrivingConfig.SAFETY_CEILING_DB) {
            val scale = DrivingConfig.SAFETY_CEILING_DB / maxGain
            for (i in gains.indices) gains[i] *= scale
        }

        return EQTarget(gains)
    }

    fun release() {
        equalizer.release()
    }
}

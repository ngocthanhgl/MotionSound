package com.motionsound.drive

import kotlin.math.abs

data class EQTarget(val bandGains: FloatArray)

class AdaptiveEQ {

    private val bandCenters = intArrayOf(60, 250, 1000, 4000, 12000)
    private val bandCount = bandCenters.size

    fun computeTarget(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        speedNorm: Float,
        depthWeight: Float,
        accelSensitivity: Float = 1f,
        cornerSensitivity: Float = 1f,
        neutralBias: FloatArray?,
        volumeReductionDb: Float = 0f
    ): EQTarget {
        val gains = FloatArray(bandCount) { 0f }
        val usedDepth = depthWeight.coerceIn(0f, 1f)

        for (i in 0 until bandCount) {
            val centerHz = bandCenters[i]
            var g = 0f

            if (centerHz in 30..250) {
                g += accelIntensity * DrivingConfig.MAX_BOOST_DB * usedDepth * accelSensitivity
            }

            if (centerHz in 800..5000) {
                g -= brakeIntensity * abs(DrivingConfig.MAX_CUT_DB) * usedDepth * 0.7f * accelSensitivity
            }

            if (centerHz in 30..150 || centerHz > 8000) {
                g += speedNorm * 1.5f * usedDepth
            }

            if (centerHz in 800..5000 && cornerIntensity > 0.1f) {
                g += cornerIntensity * DrivingConfig.CORNER_MID_CUT_DB * usedDepth * cornerSensitivity
            }

            if (centerHz in 30..250 && cornerIntensity > 0.1f) {
                g += cornerIntensity * DrivingConfig.CORNER_BASS_BOOST_DB * usedDepth * cornerSensitivity
            }

            if (centerHz > 8000 && accelIntensity > 0.1f) {
                g += accelIntensity * DrivingConfig.ACCEL_TREBLE_BOOST_DB * usedDepth * accelSensitivity
            }

            neutralBias?.let { bias ->
                if (i < bias.size) g += bias[i]
            }

            g = g.coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
            gains[i] = g
        }

        val volDb = volumeReductionDb
        if (volDb < 0f) {
            val vocalBlend = (abs(volDb) / 6f).coerceAtMost(1f) * (1f - cornerIntensity * 0.7f)
            for (i in 0 until bandCount) {
                val centerHz = bandCenters[i]
                if (centerHz in DrivingConfig.VOCAL_LOW_HZ..DrivingConfig.VOCAL_HIGH_HZ) {
                    gains[i] += DrivingConfig.VOCAL_INSIDE_BOOST_DB * vocalBlend
                } else {
                    gains[i] += DrivingConfig.VOCAL_OUTSIDE_CUT_DB * vocalBlend
                }
                gains[i] = gains[i].coerceIn(DrivingConfig.MAX_CUT_DB, DrivingConfig.MAX_BOOST_DB)
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
    }
}

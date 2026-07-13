package com.motionsound.drive

import kotlin.math.abs
import java.lang.reflect.Method

data class EQTarget(val bandGains: FloatArray)

class AdaptiveEQ(sessionId: Int) {

    private var equalizer: Any? = null
    private var bandCount = 0
    private val bandCenters: IntArray
    private var setBandLevel: Method? = null
    private var releaseMethod: Method? = null

    init {
        var eq: Any? = null
        var bc = 0
        var setBL: Method? = null
        var rel: Method? = null
        var centers = intArrayOf()
        try {
            val cls = Class.forName("android.media.audiofx.Equalizer")
            val ctor = cls.getDeclaredConstructor(java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            eq = ctor.newInstance(0, sessionId)

            cls.getMethod("setEnabled", java.lang.Boolean.TYPE).invoke(eq, true)

            val numberOfBandsMethod = cls.getMethod("numberOfBands")
            bc = (numberOfBandsMethod.invoke(eq) as Short).toInt()

            val getBandFreqRange = cls.getMethod("getBandFreqRange", java.lang.Short.TYPE)
            centers = IntArray(bc) { i ->
                val range = getBandFreqRange.invoke(eq, i.toShort()) as ShortArray
                (range[0] + range[1]) / 2
            }

            setBL = cls.getMethod("setBandLevel", java.lang.Short.TYPE, java.lang.Short.TYPE)
            rel = cls.getMethod("release")
        } catch (_: Exception) {
            eq = null
            bc = 0
        }
        equalizer = eq
        bandCount = bc
        bandCenters = centers
        setBandLevel = setBL
        releaseMethod = rel
    }

    fun applyTarget(target: EQTarget) {
        val m = setBandLevel
        val eq = equalizer
        if (m == null || eq == null) return
        for (i in 0 until bandCount.coerceAtMost(target.bandGains.size)) {
            val millibels = (target.bandGains[i] * 100).toInt().toShort()
                .coerceIn(-1500, 1500)
            try {
                m.invoke(eq, i.toShort(), millibels)
            } catch (_: Exception) {}
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
        val m = releaseMethod
        val eq = equalizer
        if (m != null && eq != null) {
            try { m.invoke(eq) } catch (_: Exception) {}
        }
    }
}

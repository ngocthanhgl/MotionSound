package com.motionsound.drive

import kotlin.math.abs
import kotlin.math.pow

class SpeedEstimator {

    private var speedMs = 0f
    private var lastGpsSpeedMs = 0f
    private var gpsLastSeenNs = 0L
    private var hasGps = false

    fun reset() {
        speedMs = 0f
        lastGpsSpeedMs = 0f
        gpsLastSeenNs = 0L
        hasGps = false
    }

    fun update(
        gpsSpeed: Float,
        gpsAccuracy: Float,
        aLongFilt: Float,
        dtS: Float,
        nowNs: Long
    ): Float {
        val gpsValid = gpsSpeed > 0.5f && gpsAccuracy >= 0f && gpsAccuracy < 25f

        if (gpsValid) {
            val timeSinceGps = if (hasGps)
                (nowNs - gpsLastSeenNs) / 1_000_000_000f else 10f

            val alpha = when {
                !hasGps -> 0.30f
                timeSinceGps > 3f -> 0.25f
                speedMs < 0.5f -> 0.20f
                gpsAccuracy < 8f -> 0.12f
                gpsAccuracy < 15f -> 0.08f
                else -> 0.05f
            }

            speedMs += alpha * (gpsSpeed - speedMs)
            speedMs = speedMs.coerceAtLeast(0f)

            lastGpsSpeedMs = gpsSpeed
            gpsLastSeenNs = nowNs
            hasGps = true
        } else {
            speedMs += aLongFilt * dtS
            speedMs = speedMs.coerceAtLeast(0f)

            val timeSinceGps = if (hasGps)
                (nowNs - gpsLastSeenNs) / 1_000_000_000f else 99f

            when {
                timeSinceGps > 5f -> speedMs *= 0.97f.pow(dtS * 50f)
                timeSinceGps > 2f -> speedMs *= 0.99f.pow(dtS * 50f)
                abs(aLongFilt) < 0.3f -> speedMs *= 0.998f.pow(dtS * 50f)
            }

            if (speedMs < 0.1f) speedMs = 0f
        }

        return speedMs
    }
}
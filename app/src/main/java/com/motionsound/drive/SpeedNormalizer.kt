package com.motionsound.drive

import kotlin.math.sqrt

class SpeedNormalizer {

    @Volatile
    var maxSpeedKmh: Int = DrivingConfig.DEFAULT_MAX_SPEED_KMH
        set(value) {
            field = value.coerceIn(20, 350)
        }

    fun normalize(speedMps: Float): Float {
        if (maxSpeedKmh <= 0) return 0f
        val maxMps = maxSpeedKmh / 3.6f
        var rawRatio = (speedMps / maxMps).coerceIn(0f, 1f)
        if (rawRatio < DrivingConfig.MIN_SPEED_RATIO) return 0f
        return sqrt(rawRatio)
    }
}

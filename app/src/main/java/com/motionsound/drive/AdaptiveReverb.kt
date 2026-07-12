package com.motionsound.drive

import android.media.audiofx.EnvironmentalReverb
import kotlin.math.exp
import kotlin.math.roundToInt

class AdaptiveReverb {

    private val reverb: EnvironmentalReverb = EnvironmentalReverb(0, 0)

    init {
        reverb.enabled = true
        reverb.setRoomLevel((-5000).toShort())
        reverb.setDecayTime(2000)
        reverb.setDiffusion(1000.toShort())
        reverb.setDensity(1000.toShort())
    }

    private var currentLevel = -5000f

    fun applyIntensity(amount: Float, dt: Float, timeConstantMs: Float = 400f) {
        val targetLevelRaw = (-9000f * (1f - amount.coerceIn(0f, 1f))).toInt()
        val targetLevel = targetLevelRaw.coerceIn(-9000, 0)
        val dtSec = dt.coerceIn(0.001f, 0.1f)
        val alpha = if (timeConstantMs > 0f)
            (1f - exp(-(dtSec * 1000f) / timeConstantMs)).coerceIn(0f, 1f)
        else 1f
        val smoothed = (currentLevel + alpha * (targetLevel - currentLevel)).roundToInt()
        currentLevel = smoothed.toFloat()
        reverb.setRoomLevel(smoothed.coerceIn(-9000, 0).toShort())
    }

    fun release() {
        reverb.release()
    }
}

package com.motionsound.drive

import android.media.audiofx.PresetReverb
import kotlin.math.roundToInt

class AdaptiveReverb {

    private val reverb: PresetReverb = PresetReverb(0, 0)

    init {
        reverb.enabled = true
        reverb.setParameter(PresetReverb.PARAM_PRESET, PresetReverb.PRESET_MEDIUM_HALL.toShort())
    }

    private var currentLevel = -5000f

    fun applyIntensity(amount: Float, dt: Float, timeConstantMs: Float = 400f) {
        val targetLevel = (-5000f * (1f - amount.coerceIn(0f, 1f))).toInt()
        val dtSec = dt.coerceIn(0.001f, 0.1f)
        val alpha = if (timeConstantMs > 0f)
            (1f - kotlin.math.exp(-(dtSec * 1000f) / timeConstantMs)).coerceIn(0f, 1f)
        else 1f
        val smoothed = (currentLevel + alpha * (targetLevel - currentLevel)).roundToInt()
        currentLevel = smoothed.toFloat()
        reverb.setParameter(PresetReverb.PARAM_ROOM_LEVEL, smoothed.toShort())
    }

    fun release() {
        reverb.release()
    }
}

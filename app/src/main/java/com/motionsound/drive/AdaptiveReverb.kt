package com.motionsound.drive

import kotlin.math.exp
import kotlin.math.roundToInt
import java.lang.reflect.Method

class AdaptiveReverb {

    private var reverb: Any? = null
    private var setRoomLevel: Method? = null
    private var releaseMethod: Method? = null

    init {
        try {
            val cls = Class.forName("android.media.audiofx.EnvironmentalReverb")
            val ctor = cls.getDeclaredConstructor(java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            reverb = ctor.newInstance(0, 0)

            cls.getMethod("setEnabled", java.lang.Boolean.TYPE).invoke(reverb, true)
            cls.getMethod("setRoomLevel", java.lang.Short.TYPE).invoke(reverb, (-5000).toShort())
            cls.getMethod("setDecayTime", java.lang.Integer.TYPE).invoke(reverb, 2000)
            cls.getMethod("setDiffusion", java.lang.Short.TYPE).invoke(reverb, 1000.toShort())
            cls.getMethod("setDensity", java.lang.Short.TYPE).invoke(reverb, 1000.toShort())

            setRoomLevel = cls.getMethod("setRoomLevel", java.lang.Short.TYPE)
            releaseMethod = cls.getMethod("release")
        } catch (_: Exception) {
            reverb = null
        }
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
        val m = setRoomLevel
        val r = reverb
        if (m != null && r != null) {
            try {
                m.invoke(r, smoothed.coerceIn(-9000, 0).toShort())
            } catch (_: Exception) {}
        }
    }

    fun release() {
        val m = releaseMethod
        val r = reverb
        if (m != null && r != null) {
            try { m.invoke(r) } catch (_: Exception) {}
        }
    }
}

package com.motionsound.drive

class SyntheticRpm {

    private var currentRpmNorm = 0f

    fun update(speedMs: Float, aLongFilt: Float, preset: VehiclePreset): Float {
        if (speedMs < 0.5f) {
            currentRpmNorm += 0.1f * (0.15f - currentRpmNorm)
            return currentRpmNorm
        }
        val baseRpmNorm = when (preset) {
            VehiclePreset.CAR -> (speedMs / 50f).coerceAtMost(0.6f)
            VehiclePreset.MOTORCYCLE -> (speedMs / 40f).coerceAtMost(0.8f)
        }
        val accelBoost = (aLongFilt / 3f).coerceIn(0f, 0.4f)
        val brakeCut = if (aLongFilt < 0) (aLongFilt / 3f).coerceIn(-0.3f, 0f) else 0f
        val target = (baseRpmNorm + accelBoost + brakeCut).coerceIn(0.05f, 1f)
        currentRpmNorm += 0.15f * (target - currentRpmNorm)
        return currentRpmNorm
    }
}

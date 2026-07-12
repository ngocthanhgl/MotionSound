package com.motionsound.drive

object VehiclePresetsProvider {

    fun lpfCutoffHz(preset: VehiclePreset): Float = when (preset) {
        VehiclePreset.CAR -> DrivingConfig.LPF_CUTOFF_HZ_CAR
        VehiclePreset.MOTORCYCLE -> DrivingConfig.LPF_CUTOFF_HZ_MOTORCYCLE
    }

    fun cornerFullScale(preset: VehiclePreset): Float = when (preset) {
        VehiclePreset.CAR -> DrivingConfig.CORNER_FULL_SCALE_MPS2
        VehiclePreset.MOTORCYCLE -> DrivingConfig.CORNER_FULL_SCALE_MPS2 * 1.5f
    }

    fun neutralEQBias(preset: VehiclePreset): FloatArray = when (preset) {
        VehiclePreset.CAR -> floatArrayOf(0f, 0f, 0f, 0f, 0f)
        VehiclePreset.MOTORCYCLE -> floatArrayOf(0f, 0f, 1.5f, 2.0f, 0f)
    }

    fun bumpThresholdMultiplier(preset: VehiclePreset): Float = when (preset) {
        VehiclePreset.CAR -> 1.0f
        VehiclePreset.MOTORCYCLE -> 1.5f
    }
}

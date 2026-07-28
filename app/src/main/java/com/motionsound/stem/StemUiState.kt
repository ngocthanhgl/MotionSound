package com.motionsound.stem

import com.motionsound.drive.DrivingState
import com.motionsound.drive.VehiclePreset

data class StemUiState(
    val speed: Float = 0f,
    val speedKmh: Float = 0f,
    val accelIntensity: Float = 0f,
    val brakeIntensity: Float = 0f,
    val cornerIntensity: Float = 0f,
    val drivingState: DrivingState = DrivingState.IDLE,
    val volumeDrums: Float = 1f,
    val volumeBass: Float = 1f,
    val volumeOther: Float = 1f,
    val volumeVocals: Float = 1f,
    val modelLoaded: Boolean = false,
    val separationProgress: Float = 0f,
    val maxSpeedKmh: Int = 140,
    val sensorSensitivity: Float = 1f,
    val vehiclePreset: VehiclePreset = VehiclePreset.CAR
)

enum class ModelLoadState { LOADING, LOADED, ERROR }

package com.motionsound.drive

enum class DrivingState {
    IDLE,
    SLOW_MANEUVERING,
    ACCELERATING,
    CRUISING,
    DECELERATING,
    CORNERING
}

enum class VehiclePreset {
    CAR,
    MOTORCYCLE
}

data class DriveUiState(
    val speed: Float = 0f,
    val speedKmh: Float = 0f,
    val speedNorm: Float = 0f,
    val accelIntensity: Float = 0f,
    val brakeIntensity: Float = 0f,
    val cornerIntensity: Float = 0f,
    val drivingState: DrivingState = DrivingState.IDLE,
    val confidence: Float = 1f,
    val eqBandGains: FloatArray = FloatArray(DrivingConfig.EQ_BAND_COUNT),
    val isServiceRunning: Boolean = false,
    val accelSensitivity: Float = 1f,
    val cornerSensitivity: Float = 1f,
    val effectDepth: Float = 0.7f,
    val responseSpeed: Float = 0.5f,
    val bumpFilterStrength: Float = 0.5f,
    val vehiclePreset: VehiclePreset = VehiclePreset.CAR,
    val maxSpeedKmh: Int = DrivingConfig.DEFAULT_MAX_SPEED_KMH,
    val volumeReductionDb: Float = 0f
)

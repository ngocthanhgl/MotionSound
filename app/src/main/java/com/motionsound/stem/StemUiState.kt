package com.motionsound.stem

import com.motionsound.drive.DrivingState
import com.motionsound.drive.VehiclePreset
import com.motionsound.sounddrive.GestureType
import com.motionsound.sounddrive.SensorProfile
import com.motionsound.sounddrive.SoundDriveMode

enum class BrakeType { REGEN, FRICTION }

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
    val modelError: String? = null,
    val downloadProgress: Float = 0f,
    val separationProgress: Float = 0f,
    val maxSpeedKmh: Int = 140,
    val sensorSensitivity: Float = 1f,
    val vehiclePreset: VehiclePreset = VehiclePreset.CAR,
    val soundDriveEnabled: Boolean = false,
    val soundDriveMode: SoundDriveMode = SoundDriveMode.DYNAMIC,
    val soundDriveIntensity: Float = 0.7f,
    val gestureIndicator: GestureType? = null,
    val roadRoughness: Float = 0f,
    val ambientMood: Float = 0.5f,
    val hillGrade: Float = 0f,
    val brakeType: BrakeType = BrakeType.FRICTION,
    val sensorProfile: SensorProfile = SensorProfile.DYNAMIC
)

enum class ModelLoadState { LOADING, LOADED, ERROR }

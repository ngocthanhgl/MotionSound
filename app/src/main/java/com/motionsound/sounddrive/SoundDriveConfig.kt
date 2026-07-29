package com.motionsound.sounddrive

enum class SoundDriveMode {
    BALANCED, DYNAMIC, IMMERSIVE, CUSTOM
}

enum class GestureType {
    ACCEL_BURST, BRAKE_HIT, CORNER_PEAK, BUMP_HIT, TUNNEL_ENTRY
}

enum class SensorProfile {
    SPORTY, DYNAMIC, RELAXED
}

data class SensorProfileData(
    val accelSensitivity: Float,
    val cornerSensitivity: Float,
    val responseSpeed: Float,
    val effectDepth: Float,
    val bumpFiltering: Float,
    val maxSpeedKmh: Int,
    val cornerPredictionS: Float,
    val gestureEnabled: Boolean
)

fun sensorProfileFor(profile: SensorProfile): SensorProfileData = when (profile) {
    SensorProfile.SPORTY -> SensorProfileData(
        accelSensitivity = 1.3f, cornerSensitivity = 1.2f, responseSpeed = 0.7f,
        effectDepth = 0.8f, bumpFiltering = 0.3f, maxSpeedKmh = 200,
        cornerPredictionS = 0.4f, gestureEnabled = true
    )
    SensorProfile.DYNAMIC -> SensorProfileData(
        accelSensitivity = 1.0f, cornerSensitivity = 1.0f, responseSpeed = 0.5f,
        effectDepth = 0.6f, bumpFiltering = 0.5f, maxSpeedKmh = 160,
        cornerPredictionS = 0.3f, gestureEnabled = true
    )
    SensorProfile.RELAXED -> SensorProfileData(
        accelSensitivity = 0.7f, cornerSensitivity = 0.6f, responseSpeed = 0.3f,
        effectDepth = 0.4f, bumpFiltering = 0.8f, maxSpeedKmh = 120,
        cornerPredictionS = 0.2f, gestureEnabled = false
    )
}

data class SoundDriveParams(
    val drumsCutoff: Float = 1f,
    val drumsResonance: Float = 0.7f,
    val drumsPan: Float = 0f,
    val bassCutoff: Float = 1f,
    val bassResonance: Float = 0.7f,
    val bassPan: Float = 0f,
    val otherCutoff: Float = 1f,
    val otherResonance: Float = 0.7f,
    val otherPan: Float = 0f,
    val vocalsCutoff: Float = 1f,
    val vocalsResonance: Float = 0.7f,
    val vocalsPan: Float = 0f,
    val masterCutoff: Float = 1f,
    val masterLowCut: Float = 0f,
    val otherBoost: Float = 1f,
    val gestureEnabled: Boolean = false
)

data class SoundDriveConfig(
    val enabled: Boolean = false,
    val mode: SoundDriveMode = SoundDriveMode.DYNAMIC,
    val intensity: Float = 0.7f,
    val sensorProfile: SensorProfile = SensorProfile.DYNAMIC,
    val customParams: SoundDriveParams = SoundDriveParams()
) {
    val effectiveParams: SoundDriveParams
        get() = if (mode == SoundDriveMode.CUSTOM) customParams
        else paramsForMode(mode, intensity)

    val effectiveSensorProfile: SensorProfileData
        get() = sensorProfileFor(sensorProfile)
}

fun paramsForMode(mode: SoundDriveMode, intensity: Float): SoundDriveParams {
    val i = intensity.coerceIn(0f, 1f)
    return when (mode) {
        SoundDriveMode.BALANCED -> SoundDriveParams(
            gestureEnabled = false,
            otherBoost = 1f + i * 0.15f
        )
        SoundDriveMode.DYNAMIC -> SoundDriveParams(
            drumsResonance = 0.5f + i * 0.3f,
            otherPan = i * 0.3f,
            gestureEnabled = true,
            masterCutoff = 0.6f + i * 0.4f,
            otherBoost = 1f + i * 0.3f
        )
        SoundDriveMode.IMMERSIVE -> SoundDriveParams(
            drumsResonance = 0.6f,
            otherResonance = 0.5f,
            otherPan = i * 0.5f,
            gestureEnabled = true,
            masterCutoff = 0.7f + i * 0.3f,
            otherBoost = 1.2f + i * 0.5f,
            vocalsCutoff = 0.8f,
            vocalsResonance = 0.5f,
            drumsCutoff = 0.9f
        )
        SoundDriveMode.CUSTOM -> SoundDriveParams()
    }
}

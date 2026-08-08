package com.motionsound.sounddrive

enum class SoundDriveMode {
    BALANCED, DYNAMIC, IMMERSIVE
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
    val cornerPredictionS: Float,
    val gestureEnabled: Boolean,
    val inputTauMs: Float = 120f,
    val smoothAttackMs: Float = 400f,
    val smoothReleaseMs: Float = 500f
)

fun sensorProfileFor(profile: SensorProfile): SensorProfileData = when (profile) {
    SensorProfile.SPORTY -> SensorProfileData(
        accelSensitivity = 1.3f, cornerSensitivity = 1.2f, responseSpeed = 0.7f,
        effectDepth = 0.8f, bumpFiltering = 0.3f,
        cornerPredictionS = 0.4f, gestureEnabled = true,
        inputTauMs = 80f, smoothAttackMs = 250f, smoothReleaseMs = 350f
    )
    SensorProfile.DYNAMIC -> SensorProfileData(
        accelSensitivity = 1.0f, cornerSensitivity = 1.0f, responseSpeed = 0.5f,
        effectDepth = 0.6f, bumpFiltering = 0.5f,
        cornerPredictionS = 0.3f, gestureEnabled = true,
        inputTauMs = 120f, smoothAttackMs = 400f, smoothReleaseMs = 500f
    )
    SensorProfile.RELAXED -> SensorProfileData(
        accelSensitivity = 0.7f, cornerSensitivity = 0.6f, responseSpeed = 0.3f,
        effectDepth = 0.4f, bumpFiltering = 0.8f,
        cornerPredictionS = 0.2f, gestureEnabled = false,
        inputTauMs = 180f, smoothAttackMs = 600f, smoothReleaseMs = 600f
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
    val gestureEnabled: Boolean = false,
    val bassFloor: Float = 0.15f,
    val reverbSendMax: Float = 0.35f,
    val tremoloDepthMax: Float = 0.25f,
    val drumsEnter: Float = 0f,
    val drumsFull: Float = 0.3f,
    val bassEnter: Float = 0.2f,
    val bassFull: Float = 0.5f,
    val otherEnter: Float = 0.45f,
    val otherFull: Float = 0.75f,
    val vocalsEnter: Float = 0.65f,
    val vocalsFull: Float = 0.95f
)

data class SoundDriveConfig(
    val enabled: Boolean = false,
    val mode: SoundDriveMode = SoundDriveMode.DYNAMIC,
    val intensity: Float = 0.7f,
    val sensorProfile: SensorProfile = SensorProfile.DYNAMIC,
    val gpsMode: Boolean = false
) {
    val effectiveParams: SoundDriveParams
        get() = paramsForMode(mode, intensity)

    val effectiveSensorProfile: SensorProfileData
        get() = sensorProfileFor(sensorProfile)
}

fun paramsForMode(mode: SoundDriveMode, intensity: Float): SoundDriveParams {
    val i = intensity.coerceIn(0f, 1f)
    return when (mode) {
        SoundDriveMode.BALANCED -> SoundDriveParams(
            gestureEnabled = false,
            otherBoost = 1f + i * 0.15f,
            bassFloor = 0.2f,
            reverbSendMax = 0.25f,
            tremoloDepthMax = 0.15f,
            drumsEnter = 0f, drumsFull = 0.2f,
            bassEnter = 0.15f, bassFull = 0.35f,
            otherEnter = 0.3f, otherFull = 0.5f,
            vocalsEnter = 0.4f, vocalsFull = 0.55f
        )
        SoundDriveMode.DYNAMIC -> SoundDriveParams(
            drumsResonance = 0.5f + i * 0.3f,
            otherPan = i * 0.3f,
            gestureEnabled = true,
            masterCutoff = 0.6f + i * 0.4f,
            otherBoost = 1f + i * 0.3f,
            bassFloor = 0.25f,
            reverbSendMax = 0.4f,
            tremoloDepthMax = 0.25f,
            drumsEnter = 0f, drumsFull = 0.3f,
            bassEnter = 0.2f, bassFull = 0.5f,
            otherEnter = 0.45f, otherFull = 0.75f,
            vocalsEnter = 0.5f, vocalsFull = 0.85f
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
            drumsCutoff = 0.9f,
            bassFloor = 0.35f,
            reverbSendMax = 0.5f,
            tremoloDepthMax = 0.35f,
            drumsEnter = 0f, drumsFull = 0.15f,
            bassEnter = 0.05f, bassFull = 0.25f,
            otherEnter = 0.15f, otherFull = 0.4f,
            vocalsEnter = 0.25f, vocalsFull = 0.5f
        )
    }
}

package com.motionsound.sounddrive

enum class SoundDriveMode {
    BALANCED,
    DYNAMIC,
    IMMERSIVE,
    CUSTOM
}

enum class GestureType {
    ACCEL_BURST,
    BRAKE_HIT,
    CORNER_PEAK
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
    val customParams: SoundDriveParams = SoundDriveParams()
) {
    val effectiveParams: SoundDriveParams
        get() = if (mode == SoundDriveMode.CUSTOM) customParams
        else paramsForMode(mode, intensity)
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

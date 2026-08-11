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
    val drumsFloor: Float = 0.25f,
    val bassFloor: Float = 0.15f,
    val otherFloor: Float = 0.15f,
    val vocalsFloor: Float = 0.2f,
    val reverbSendMax: Float = 0.35f,
    val idleReverbMax: Float = 0f,
    val idleEchoMax: Float = 0f,
    val echoSendMax: Float = 0.25f,
    val tremoloDepthMax: Float = 0.25f,
    val warpDepthMax: Float = 0f,
    val warpRateMax: Float = 2f,
    val brakeRetreatMax: Float = 0f,
    val idleDrumsScale: Float = 1f,
    val drumsEnter: Float = 0f,
    val drumsFull: Float = 0.3f,
    val bassEnter: Float = 0.2f,
    val bassFull: Float = 0.5f,
    val otherEnter: Float = 0.45f,
    val otherFull: Float = 0.75f,
    val vocalsEnter: Float = 0.65f,
    val vocalsFull: Float = 0.95f,
    val drumsDelayMs: Float = 0f,
    val bassDelayMs: Float = 2000f,
    val otherDelayMs: Float = 4000f,
    val vocalsDelayMs: Float = 6000f,
    val kickHysteresis: Float = 0.1f,
    val layerAccelBoost: Float = 0.1f,
    val buildFloor: Float = 0.05f,
    val buildLapseMs: Float = 1200f,
    val paceScaleMin: Float = 0.35f
)

data class SoundDriveConfig(
    val enabled: Boolean = false,
    val mode: SoundDriveMode = SoundDriveMode.DYNAMIC,
    val intensity: Float = 0.7f,
    val gpsMode: Boolean = false,
    val speedCapKmh: Float = 58f
) {
    val effectiveParams: SoundDriveParams
        get() = paramsForMode(mode, intensity)

    val effectiveSensorProfile: SensorProfileData
        get() = sensorProfileFor(SensorProfile.DYNAMIC)
}

fun paramsForMode(mode: SoundDriveMode, intensity: Float): SoundDriveParams {
    val i = intensity.coerceIn(0f, 1f)
    return when (mode) {
        SoundDriveMode.BALANCED -> SoundDriveParams(
            gestureEnabled = true,
            layerAccelBoost = 0.6f,
            otherBoost = 1f + i * 0.15f,
            drumsFloor = 0.25f,
            bassFloor = 0.15f,
            otherFloor = 0.2f,
            vocalsFloor = 0f,
            reverbSendMax = 0.25f,
            idleReverbMax = 0.45f,
            idleEchoMax = 0.1f,
            echoSendMax = 0.18f,
            tremoloDepthMax = 0.15f,
            warpDepthMax = 0.2f,
            brakeRetreatMax = 0.25f,
            idleDrumsScale = 0.6f,
            drumsEnter = 0f, drumsFull = 0.35f,
            bassEnter = 0.1f, bassFull = 0.45f,
            otherEnter = 0.12f, otherFull = 0.55f,
            vocalsEnter = 0.25f, vocalsFull = 0.65f,
            drumsDelayMs = 400f, bassDelayMs = 0f,
            otherDelayMs = 700f, vocalsDelayMs = 2200f,
            buildLapseMs = 600f
        )
        SoundDriveMode.DYNAMIC -> SoundDriveParams(
            drumsResonance = 0.5f + i * 0.3f,
            otherPan = i * 0.3f,
            gestureEnabled = true,
            layerAccelBoost = 0.7f,
            masterCutoff = 0.6f + i * 0.4f,
            otherBoost = 1f + i * 0.3f,
            drumsFloor = 0.2f,
            bassFloor = 0.18f,
            otherFloor = 0.15f,
            vocalsFloor = 0f,
            reverbSendMax = 0.4f,
            idleReverbMax = 0.5f,
            idleEchoMax = 0.12f,
            echoSendMax = 0.25f,
            tremoloDepthMax = 0.25f,
            warpDepthMax = 0.3f,
            brakeRetreatMax = 0.3f,
            idleDrumsScale = 0.6f,
            drumsEnter = 0f, drumsFull = 0.35f,
            bassEnter = 0.12f, bassFull = 0.6f,
            otherEnter = 0.18f, otherFull = 0.75f,
            vocalsEnter = 0.4f, vocalsFull = 0.85f,
            drumsDelayMs = 550f, bassDelayMs = 0f,
            otherDelayMs = 900f, vocalsDelayMs = 2800f,
            buildLapseMs = 600f
        )
        SoundDriveMode.IMMERSIVE -> SoundDriveParams(
            drumsResonance = 0.6f,
            otherResonance = 0.5f,
            otherPan = i * 0.5f,
            gestureEnabled = true,
            layerAccelBoost = 0.8f,
            masterCutoff = 0.7f + i * 0.3f,
            otherBoost = 1.2f + i * 0.5f,
            vocalsCutoff = 0.8f,
            vocalsResonance = 0.5f,
            drumsCutoff = 0.9f,
            drumsFloor = 0.15f,
            bassFloor = 0.25f,
            otherFloor = 0.1f,
            vocalsFloor = 0f,
            reverbSendMax = 0.5f,
            idleReverbMax = 0.6f,
            idleEchoMax = 0.15f,
            echoSendMax = 0.3f,
            tremoloDepthMax = 0.35f,
            warpDepthMax = 0.4f,
            brakeRetreatMax = 0.35f,
            idleDrumsScale = 0.7f,
            drumsEnter = 0f, drumsFull = 0.2f,
            bassEnter = 0.05f, bassFull = 0.3f,
            otherEnter = 0.1f, otherFull = 0.45f,
            vocalsEnter = 0.3f, vocalsFull = 0.55f,
            drumsDelayMs = 800f, bassDelayMs = 0f,
            otherDelayMs = 1400f, vocalsDelayMs = 4000f,
            buildLapseMs = 900f
        )
    }
}

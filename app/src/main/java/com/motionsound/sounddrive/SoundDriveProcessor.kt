package com.motionsound.sounddrive

import com.motionsound.drive.DrivingState
import com.motionsound.stem.StemMixer

class SoundDriveProcessor(private val mixer: StemMixer) {

    private var prevAccelIntensity = 0f
    private var prevBrakeIntensity = 0f
    private var prevCornerIntensity = 0f

    private var gestureDrumsBoost = 0f
    private var gestureBassBoost = 0f
    private var gestureVocalsCut = 0f
    private var gestureOtherBoost = 0f

    fun update(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        speedIntensity: Float,
        drivingState: DrivingState,
        config: SoundDriveConfig
    ): GestureType? {
        val params = config.effectiveParams

        if (!config.enabled) {
            mixer.volumeDrums = 1f; mixer.volumeBass = 1f
            mixer.volumeOther = 1f; mixer.volumeVocals = 1f
            mixer.drumsCutoff = 1f; mixer.drumsResonance = 0.707f; mixer.drumsPan = 0f
            mixer.bassCutoff = 1f; mixer.bassResonance = 0.707f; mixer.bassPan = 0f
            mixer.otherCutoff = 1f; mixer.otherResonance = 0.707f; mixer.otherPan = 0f
            mixer.vocalsCutoff = 1f; mixer.vocalsResonance = 0.707f; mixer.vocalsPan = 0f
            mixer.masterCutoff = 1f; mixer.masterLowCut = 0f
            mixer.gestureDrumsBoost = 0f; mixer.gestureBassBoost = 0f
            mixer.gestureVocalsCut = 0f; mixer.gestureOtherBoost = 0f
            return null
        }

        decayGestures()

        var gesture: GestureType? = null
        if (params.gestureEnabled) {
            gesture = detectGesture(accelIntensity, brakeIntensity, cornerIntensity, drivingState)
            if (gesture != null) applyGesture(gesture)
        }

        val i = config.intensity.coerceIn(0f, 1f)
        val (baseDrums, baseBass, baseOther, baseVocals) = when (config.mode) {
            SoundDriveMode.BALANCED -> {
                val accel = accelIntensity * i
                val corner = cornerIntensity * 0.6f * i
                val speed = speedIntensity * i
                VolumeSet(
                    drums = lerp(1f, 1.2f, maxOf(accel, corner)),
                    bass = lerp(1f, 1.15f, maxOf(accel * 0.7f, speed * 0.5f)),
                    other = lerp(1f, 1.1f, cornerIntensity * 0.3f * i),
                    vocals = lerp(1f, 0.7f, speed * 0.4f + accel * 0.2f)
                )
            }
            SoundDriveMode.DYNAMIC -> {
                VolumeSet(
                    drums = lerp(0.5f, 2f, maxOf(accelIntensity, cornerIntensity * 0.7f) * i),
                    bass = lerp(0.4f, 1.8f, maxOf(accelIntensity * 0.7f, speedIntensity * 0.8f) * i),
                    other = lerp(0.6f, 1.5f, cornerIntensity * 0.6f * i),
                    vocals = lerp(0.3f, 1f, (1f - (speedIntensity * 0.5f + accelIntensity * 0.2f)) * i)
                )
            }
            SoundDriveMode.IMMERSIVE -> {
                val a = accelIntensity * i
                val s = speedIntensity * i
                VolumeSet(
                    drums = lerp(0.7f, 1.5f, maxOf(a, cornerIntensity * 0.5f * i)),
                    bass = lerp(0.6f, 1.4f, maxOf(a * 0.5f, s * 0.6f)),
                    other = lerp(1.2f, 2f, 1f - s * 0.3f) * params.otherBoost,
                    vocals = lerp(0.5f, 1f, 1f - s * 0.4f)
                )
            }
            SoundDriveMode.CUSTOM -> VolumeSet(1f, 1f, 1f, 1f)
        }

        mixer.volumeDrums = (baseDrums + gestureDrumsBoost).coerceIn(0f, 2.5f)
        mixer.volumeBass = (baseBass + gestureBassBoost).coerceIn(0f, 2.5f)
        mixer.volumeOther = (baseOther + gestureOtherBoost).coerceIn(0f, 2.5f)
        mixer.volumeVocals = (baseVocals + gestureVocalsCut).coerceIn(0f, 2.5f)

        when (config.mode) {
            SoundDriveMode.BALANCED -> {
                val amt = speedIntensity * 0.15f * i
                mixer.drumsCutoff = lerp(1f, 0.8f, amt)
                mixer.bassCutoff = lerp(1f, 0.85f, amt)
                mixer.otherCutoff = 1f
                mixer.vocalsCutoff = lerp(1f, 0.9f, amt)
                mixer.masterCutoff = lerp(1f, 0.9f, amt)
                mixer.masterLowCut = lerp(0f, 0.003f, amt)
                mixer.drumsPan = lerp(0f, 0.1f, cornerIntensity * i)
                mixer.bassPan = 0f
                mixer.otherPan = lerp(0f, 0.15f, cornerIntensity * i)
                mixer.vocalsPan = lerp(0f, 0.1f, cornerIntensity * i)
                mixer.drumsResonance = 0.707f
                mixer.bassResonance = 0.707f
                mixer.otherResonance = 0.707f
                mixer.vocalsResonance = 0.707f
            }
            SoundDriveMode.DYNAMIC -> {
                val af = accelIntensity * i
                val sf = speedIntensity * i
                mixer.drumsCutoff = lerp(0.4f, 1f, maxOf(af, sf * 0.7f))
                mixer.bassCutoff = lerp(0.3f, 1f, maxOf(af * 0.8f, sf * 0.6f))
                mixer.otherCutoff = lerp(0.5f, 1f, sf * 0.5f)
                mixer.vocalsCutoff = lerp(0.6f, 1f, sf * 0.4f)
                mixer.masterCutoff = lerp(0.4f, 1f, maxOf(af * 0.9f, sf * 0.8f))
                mixer.masterLowCut = lerp(0f, 0.008f, sf * 0.5f)
                mixer.drumsPan = lerp(0f, 0.2f, cornerIntensity * i)
                mixer.bassPan = 0f
                mixer.otherPan = lerp(0f, 0.4f, cornerIntensity * i)
                mixer.vocalsPan = lerp(0f, 0.15f, cornerIntensity * i)
                mixer.drumsResonance = params.drumsResonance
                mixer.bassResonance = 0.6f
                mixer.otherResonance = 0.5f
                mixer.vocalsResonance = 0.5f
            }
            SoundDriveMode.IMMERSIVE -> {
                val slow = (accelIntensity * 0.5f + speedIntensity * 0.5f) * i * 0.5f
                mixer.drumsCutoff = lerp(0.7f, 1f, slow)
                mixer.bassCutoff = lerp(0.6f, 1f, slow)
                mixer.otherCutoff = lerp(0.4f, 0.9f, slow)
                mixer.vocalsCutoff = lerp(0.7f, 0.9f, slow * 0.5f)
                mixer.masterCutoff = lerp(0.6f, 1f, slow * 0.7f)
                mixer.masterLowCut = lerp(0f, 0.006f, speedIntensity * 0.3f * i)
                mixer.drumsPan = lerp(0f, 0.15f, cornerIntensity * i)
                mixer.bassPan = 0f
                mixer.otherPan = lerp(0f, 0.5f, cornerIntensity * i)
                mixer.vocalsPan = lerp(0f, 0.2f, cornerIntensity * i)
                mixer.drumsResonance = 0.5f
                mixer.bassResonance = 0.5f
                mixer.otherResonance = 0.4f
                mixer.vocalsResonance = 0.4f
            }
            SoundDriveMode.CUSTOM -> {
                mixer.drumsCutoff = params.drumsCutoff
                mixer.drumsResonance = params.drumsResonance
                mixer.drumsPan = params.drumsPan
                mixer.bassCutoff = params.bassCutoff
                mixer.bassResonance = params.bassResonance
                mixer.bassPan = params.bassPan
                mixer.otherCutoff = params.otherCutoff
                mixer.otherResonance = params.otherResonance
                mixer.otherPan = params.otherPan
                mixer.vocalsCutoff = params.vocalsCutoff
                mixer.vocalsResonance = params.vocalsResonance
                mixer.vocalsPan = params.vocalsPan
                mixer.masterCutoff = params.masterCutoff
                mixer.masterLowCut = params.masterLowCut
            }
        }

        return gesture
    }

    private fun detectGesture(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        drivingState: DrivingState
    ): GestureType? {
        val result: GestureType? = when {
            accelIntensity > 0.5f && prevAccelIntensity < 0.3f
                && drivingState == DrivingState.ACCELERATING -> GestureType.ACCEL_BURST
            brakeIntensity > 0.4f && prevBrakeIntensity < 0.2f
                && drivingState == DrivingState.DECELERATING -> GestureType.BRAKE_HIT
            cornerIntensity > 0.5f && cornerIntensity > prevCornerIntensity * 1.5f
                && drivingState == DrivingState.CORNERING -> GestureType.CORNER_PEAK
            else -> null
        }
        prevAccelIntensity = accelIntensity
        prevBrakeIntensity = brakeIntensity
        prevCornerIntensity = cornerIntensity
        return result
    }

    private fun applyGesture(gesture: GestureType) {
        when (gesture) {
            GestureType.ACCEL_BURST -> { gestureDrumsBoost = 0.35f; gestureBassBoost = 0.2f }
            GestureType.BRAKE_HIT -> { gestureVocalsCut = -0.3f; gestureOtherBoost = 0.25f }
            GestureType.CORNER_PEAK -> { gestureDrumsBoost = 0.15f }
        }
    }

    private fun decayGestures() {
        gestureDrumsBoost *= 0.7f
        if (gestureDrumsBoost < 0.01f) gestureDrumsBoost = 0f
        gestureBassBoost *= 0.7f
        if (gestureBassBoost < 0.01f) gestureBassBoost = 0f
        gestureVocalsCut *= 0.7f
        if (gestureVocalsCut > -0.01f) gestureVocalsCut = 0f
        gestureOtherBoost *= 0.7f
        if (gestureOtherBoost < 0.01f) gestureOtherBoost = 0f
    }

    fun resetGestures() {
        gestureDrumsBoost = 0f; gestureBassBoost = 0f
        gestureVocalsCut = 0f; gestureOtherBoost = 0f
    }

    private data class VolumeSet(val drums: Float, val bass: Float, val other: Float, val vocals: Float)
    private companion object {
        fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
    }
}

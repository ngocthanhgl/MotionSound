package com.motionsound.sounddrive

import com.motionsound.drive.DrivingState
import com.motionsound.stem.BrakeType
import com.motionsound.stem.StemMixer

class SoundDriveProcessor(private val mixer: StemMixer) {

    private var prevAccelIntensity = 0f
    private var prevBrakeIntensity = 0f
    private var prevCornerIntensity = 0f
    private var prevRoadRoughness = 0f
    private var prevAmbientMood = 0.5f

    private var gestureDrumsBoost = 0f
    private var gestureBassBoost = 0f
    private var gestureVocalsCut = 0f
    private var gestureOtherBoost = 0f

    private var tunnelRampTimer = 0f
    private var inTunnel = false

    fun update(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        speedGate: Float,
        drivingState: DrivingState,
        config: SoundDriveConfig,
        roadRoughness: Float = 0f,
        ambientMood: Float = 0.5f,
        hillGrade: Float = 0f,
        brakeType: BrakeType = BrakeType.FRICTION,
        verticalJounce: Float = 0f
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
            resetGestures()
            return null
        }

        val profile = config.effectiveSensorProfile
        decayGestures()

        var gesture: GestureType? = null
        if (profile.gestureEnabled) {
            gesture = detectGesture(accelIntensity, brakeIntensity, cornerIntensity, verticalJounce, ambientMood, drivingState)
            if (gesture != null) applyGesture(gesture)
        }

        val i = config.intensity.coerceIn(0f, 1f)
        val (baseDrums, baseBass, baseOther, baseVocals) = when (config.mode) {
            SoundDriveMode.BALANCED -> {
                val ac = accelIntensity * i
                val co = cornerIntensity * i
                VolumeSet(
                    drums = lerp(0f, 1f, speedGate * 0.3f + maxOf(ac, co * 0.5f) * 0.5f),
                    bass = lerp(0f, 1f, speedGate * 0.3f + ac * 0.5f),
                    other = lerp(0.6f, 1f, speedGate * 0.2f + co * 0.2f),
                    vocals = lerp(0f, 1f, speedGate * 0.3f + ac * 0.4f)
                )
            }
            SoundDriveMode.DYNAMIC -> {
                val ac = accelIntensity * i
                val co = cornerIntensity * i
                VolumeSet(
                    drums = lerp(0f, 1.2f, speedGate * 0.3f + maxOf(ac, co * 0.5f) * 0.6f),
                    bass = lerp(0f, 1.3f, speedGate * 0.3f + ac * 0.6f),
                    other = lerp(0.6f, 1.1f, speedGate * 0.2f + co * 0.3f),
                    vocals = lerp(0f, 1f, speedGate * 0.3f + ac * 0.4f)
                )
            }
            SoundDriveMode.IMMERSIVE -> {
                val a = accelIntensity * i
                val co = cornerIntensity * i
                VolumeSet(
                    drums = lerp(0f, 0.8f, speedGate * 0.3f + maxOf(a * 0.5f, co * 0.3f) * 0.4f),
                    bass = lerp(0f, 0.7f, speedGate * 0.3f + a * 0.3f),
                    other = lerp(1f, 1.5f, speedGate * 0.2f + a * 0.2f + co * 0.1f),
                    vocals = lerp(0f, 0.4f, speedGate * 0.2f + a * 0.2f)
                )
            }
            SoundDriveMode.CUSTOM -> VolumeSet(1f, 1f, 1f, 1f)
        }

        val roadMod = roadRoughness * 0.15f * profile.effectDepth
        val brakeDrumsBoost = brakeIntensity * if (brakeType == BrakeType.REGEN) 0.15f else 0.35f

        val climbBoost = maxOf(hillGrade, 0f) * 0.1f * profile.effectDepth
        val descentBoost = maxOf(-hillGrade, 0f) * 0.08f * profile.effectDepth

        val nightCut = (1f - ambientMood) * 0.15f

        updateTunnelRamp(ambientMood)

        mixer.volumeDrums = sni((baseDrums + gestureDrumsBoost + brakeDrumsBoost).coerceIn(0f, 2.5f), 1f)
        mixer.volumeBass = sni((baseBass * (1f + climbBoost) + gestureBassBoost).coerceIn(0f, 2.5f), 1f)
        mixer.volumeOther = sni((baseOther * (1f + roadMod + tunnelRampTimer) + gestureOtherBoost).coerceIn(0f, 2.5f), 1f)
        mixer.volumeVocals = sni((baseVocals * (1f - nightCut + descentBoost) + gestureVocalsCut).coerceIn(0f, 2.5f), 1f)

        val ed = profile.effectDepth
        when (config.mode) {
            SoundDriveMode.BALANCED -> {
                val amt = sni(speedGate * 0.15f * i * ed, 0f)
                mixer.drumsCutoff = sni(lerp(1f, 0.8f, amt))
                mixer.bassCutoff = sni(lerp(1f, 0.85f, amt))
                mixer.otherCutoff = sni(lerp(1f, 0.9f, amt * (1f - roadRoughness * 0.3f)))
                mixer.vocalsCutoff = sni(lerp(1f, 0.9f, amt))
                mixer.masterCutoff = sni(lerp(1f, 0.9f, amt * (1f - nightCut)))
                mixer.masterLowCut = sni(lerp(0f, 0.003f, amt), 0f)
                mixer.drumsPan = sni(lerp(0f, 0.1f, cornerIntensity * i), 0f)
                mixer.bassPan = 0f
                mixer.otherPan = sni(lerp(0f, 0.15f, cornerIntensity * i), 0f)
                mixer.vocalsPan = sni(lerp(0f, 0.1f, cornerIntensity * i), 0f)
                mixer.drumsResonance = 0.707f
                mixer.bassResonance = 0.707f
                mixer.otherResonance = 0.707f
                mixer.vocalsResonance = 0.707f
            }
            SoundDriveMode.DYNAMIC -> {
                val af = sni(accelIntensity * i * ed, 0f)
                val sf = sni(speedGate * 0.7f * ed, 0f)
                mixer.drumsCutoff = sni(lerp(0.4f, 1f, maxOf(af, sf)))
                mixer.bassCutoff = sni(lerp(0.3f, 1f, maxOf(af * 0.8f, sf * 0.6f)))
                mixer.otherCutoff = sni(lerp(0.5f, 1f, sf * 0.5f * (1f - nightCut * 0.5f)))
                mixer.vocalsCutoff = sni(lerp(0.6f, 1f, sf * 0.4f * (1f - nightCut)))
                mixer.masterCutoff = sni(lerp(0.4f, 1f, maxOf(af * 0.9f, sf * 0.8f) * (1f - nightCut * 0.3f)))
                mixer.masterLowCut = sni(lerp(0f, 0.008f, sf * 0.5f), 0f)
                mixer.drumsPan = sni(lerp(0f, 0.2f, cornerIntensity * i), 0f)
                mixer.bassPan = 0f
                mixer.otherPan = sni(lerp(0f, 0.4f, cornerIntensity * i), 0f)
                mixer.vocalsPan = sni(lerp(0f, 0.15f, cornerIntensity * i), 0f)
                mixer.drumsResonance = sni(params.drumsResonance, 0.707f)
                mixer.bassResonance = 0.6f
                mixer.otherResonance = 0.5f
                mixer.vocalsResonance = 0.5f
            }
            SoundDriveMode.IMMERSIVE -> {
                val slow = sni((accelIntensity * 0.5f + speedGate * 0.5f) * i * 0.5f * ed, 0f)
                mixer.drumsCutoff = sni(lerp(0.7f, 1f, slow))
                mixer.bassCutoff = sni(lerp(0.6f, 1f, slow))
                mixer.otherCutoff = sni(lerp(0.4f, 0.9f, slow * (1f - roadRoughness * 0.2f)))
                mixer.vocalsCutoff = sni(lerp(0.7f, 0.9f, slow * 0.5f * (1f - nightCut)))
                mixer.masterCutoff = sni(lerp(0.6f, 1f, slow * 0.7f * (1f - nightCut * 0.2f)))
                mixer.masterLowCut = sni(lerp(0f, 0.006f, speedGate * 0.3f * i), 0f)
                mixer.drumsPan = sni(lerp(0f, 0.15f, cornerIntensity * i), 0f)
                mixer.bassPan = 0f
                mixer.otherPan = sni(lerp(0f, 0.5f, cornerIntensity * i), 0f)
                mixer.vocalsPan = sni(lerp(0f, 0.2f, cornerIntensity * i), 0f)
                mixer.drumsResonance = 0.5f
                mixer.bassResonance = 0.5f
                mixer.otherResonance = 0.4f
                mixer.vocalsResonance = 0.4f
            }
            SoundDriveMode.CUSTOM -> {
                mixer.drumsCutoff = sni(params.drumsCutoff)
                mixer.drumsResonance = sni(params.drumsResonance, 0.707f)
                mixer.drumsPan = sni(params.drumsPan, 0f)
                mixer.bassCutoff = sni(params.bassCutoff)
                mixer.bassResonance = sni(params.bassResonance, 0.707f)
                mixer.bassPan = sni(params.bassPan, 0f)
                mixer.otherCutoff = sni(params.otherCutoff)
                mixer.otherResonance = sni(params.otherResonance, 0.707f)
                mixer.otherPan = sni(params.otherPan, 0f)
                mixer.vocalsCutoff = sni(params.vocalsCutoff)
                mixer.vocalsResonance = sni(params.vocalsResonance, 0.707f)
                mixer.vocalsPan = sni(params.vocalsPan, 0f)
                mixer.masterCutoff = sni(params.masterCutoff)
                mixer.masterLowCut = sni(params.masterLowCut, 0f)
            }
        }

        prevRoadRoughness = roadRoughness
        prevAmbientMood = ambientMood

        return gesture
    }

    private fun updateTunnelRamp(ambientMood: Float) {
        val drop = prevAmbientMood - ambientMood
        if (drop > 0.4f) {
            inTunnel = true
            tunnelRampTimer = 0.15f
        }
        if (inTunnel) {
            tunnelRampTimer *= 0.98f
            if (tunnelRampTimer < 0.005f) {
                tunnelRampTimer = 0f
                inTunnel = false
            }
        }
    }

    private fun detectGesture(
        accelIntensity: Float,
        brakeIntensity: Float,
        cornerIntensity: Float,
        verticalJounce: Float,
        ambientMood: Float,
        drivingState: DrivingState
    ): GestureType? {
        val moodDrop = prevAmbientMood - ambientMood
        if (moodDrop > 0.5f) return GestureType.TUNNEL_ENTRY

        val result: GestureType? = when {
            verticalJounce > 0.7f && verticalJounce > prevRoadRoughness * 3f -> GestureType.BUMP_HIT
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
            GestureType.BRAKE_HIT -> { gestureDrumsBoost = 0.35f }
            GestureType.CORNER_PEAK -> { gestureDrumsBoost = 0.15f }
            GestureType.BUMP_HIT -> { gestureOtherBoost = 0.2f; gestureBassBoost = 0.1f }
            GestureType.TUNNEL_ENTRY -> { gestureOtherBoost = 0.25f }
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
        fun sni(v: Float, default: Float = 1f) = if (v.isNaN() || v.isInfinite()) default else v
    }
}

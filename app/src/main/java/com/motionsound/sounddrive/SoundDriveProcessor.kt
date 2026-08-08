package com.motionsound.sounddrive

import com.motionsound.drive.DrivingState
import com.motionsound.stem.BrakeType
import com.motionsound.stem.StemMixer
import kotlin.math.exp
import kotlin.math.sign

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

    private var drumsEnvelope = 0f
    private var bassEnvelope = 0f
    private var otherEnvelope = 0f
    private var vocalsEnvelope = 0f
    private var drumsArmedNs = 0L
    private var bassArmedNs = 0L
    private var otherArmedNs = 0L
    private var vocalsArmedNs = 0L
    private var buildOriginNs = 0L
    private var motionSmooth = 0f
    private var layerLevelSmooth = 0f
    private var lastUpdateNs = 0L

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
        verticalJounce: Float = 0f,
        signedCornerPan: Float = 0f
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
            mixer.reverbWet = 0f
            mixer.tremoloDepth = 0f
            mixer.vocalsGateActive = false
            mixer.vocalsGateTarget = 1f
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
        val ed = profile.effectDepth

        val nowNs = System.nanoTime()
        val dtSec = if (lastUpdateNs == 0L) 0.016f else ((nowNs - lastUpdateNs) / 1e9f).coerceIn(0f, 0.5f)
        lastUpdateNs = nowNs
        val attackCoef = 1f - exp(-dtSec / (profile.smoothAttackMs / 1000f).coerceAtLeast(0.01f))
        val releaseCoef = 1f - exp(-dtSec / (profile.smoothReleaseMs / 1000f).coerceAtLeast(0.01f))

        val rawMotion = (maxOf(accelIntensity, brakeIntensity, cornerIntensity * 0.6f, speedGate) * i).coerceIn(0f, 1f)
        motionSmooth += (rawMotion - motionSmooth) * if (rawMotion > motionSmooth) attackCoef else releaseCoef
        val motion = motionSmooth.coerceIn(0f, 1f)

        val regenRetreat = if (brakeType == BrakeType.REGEN) brakeIntensity * 0.5f else 0f
        var rawLayer = maxOf(accelIntensity, speedGate).coerceIn(0f, 1f)
        if (regenRetreat > 0f) rawLayer *= (1f - regenRetreat * brakeIntensity)
        rawLayer = rawLayer.coerceIn(0f, 1f)
        layerLevelSmooth += (rawLayer - layerLevelSmooth) * if (rawLayer > layerLevelSmooth) attackCoef else releaseCoef
        val layerLevel = layerLevelSmooth.coerceIn(0f, 1f)

        drumsArmedNs = kickArm(drumsArmedNs, layerLevel, params.drumsEnter, params.kickHysteresis, nowNs)
        bassArmedNs = kickArm(bassArmedNs, layerLevel, params.bassEnter, params.kickHysteresis, nowNs)
        otherArmedNs = kickArm(otherArmedNs, layerLevel, params.otherEnter, params.kickHysteresis, nowNs)
        vocalsArmedNs = kickArm(vocalsArmedNs, layerLevel, params.vocalsEnter, params.kickHysteresis, nowNs)

        if (layerLevel < 0.05f) buildOriginNs = 0L
        else if (buildOriginNs == 0L) buildOriginNs = nowNs

        val targetDrums = if (kickReady(drumsArmedNs, buildOriginNs, nowNs, params.drumsDelayMs)) targetDrumsFor(config.mode, params, layerLevel) else 0f
        val targetBass = if (kickReady(bassArmedNs, buildOriginNs, nowNs, params.bassDelayMs)) targetBassFor(config.mode, params, layerLevel) else 0f
        val targetOther = if (kickReady(otherArmedNs, buildOriginNs, nowNs, params.otherDelayMs)) targetOtherFor(config.mode, params, layerLevel) else 0f
        val targetVocals = if (kickReady(vocalsArmedNs, buildOriginNs, nowNs, params.vocalsDelayMs)) targetVocalsFor(config.mode, params, layerLevel) else 0f

        drumsEnvelope += (targetDrums - drumsEnvelope) * if (targetDrums > drumsEnvelope) attackCoef else releaseCoef
        bassEnvelope += (targetBass - bassEnvelope) * if (targetBass > bassEnvelope) attackCoef else releaseCoef
        otherEnvelope += (targetOther - otherEnvelope) * if (targetOther > otherEnvelope) attackCoef else releaseCoef
        vocalsEnvelope += (targetVocals - vocalsEnvelope) * if (targetVocals > vocalsEnvelope) attackCoef else releaseCoef

        val brakeReverb = if (brakeType == BrakeType.FRICTION) brakeIntensity * 0.8f else 0f
        val nightCut = (1f - ambientMood) * 0.15f

        updateTunnelRamp(ambientMood)

        mixer.volumeDrums = sni((drumsEnvelope + gestureDrumsBoost).coerceIn(0f, 2f), 1f)
        mixer.volumeBass = sni((params.bassFloor + bassEnvelope + gestureBassBoost).coerceIn(0f, 2f), 1f)
        mixer.volumeOther = sni((otherEnvelope * (1f + tunnelRampTimer) + gestureOtherBoost).coerceIn(0f, 2f), 1f)
        val vocalsAuto = (vocalsEnvelope * (1f - nightCut) + gestureVocalsCut).coerceIn(0f, 2f)
        mixer.vocalsGateActive = true
        mixer.vocalsGateTarget = sni(vocalsAuto, 1f)
        mixer.volumeVocals = sni(vocalsAuto, 1f)

        val idleMuffle = (1f - motion.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val masterTarget = params.masterCutoff * (1f - idleMuffle * 0.45f * ed)
        mixer.masterCutoff = sni(masterTarget, 1f)
        mixer.masterLowCut = sni(params.masterLowCut + idleMuffle * 0.004f, 0f)

        val cornerAmt = (cornerIntensity * i).coerceIn(0f, 1f)
        mixer.drumsCutoff = sni(lerp(params.drumsCutoff * 0.55f, params.drumsCutoff, motion), 1f)
        mixer.bassCutoff = sni(lerp(params.bassCutoff * 0.5f, params.bassCutoff, motion), 1f)
        mixer.otherCutoff = sni(lerp(params.otherCutoff * 0.55f, params.otherCutoff, motion * 0.9f + cornerAmt * 0.1f), 1f)
        mixer.vocalsCutoff = sni(lerp(params.vocalsCutoff * 0.6f, params.vocalsCutoff, motion * 0.95f), 1f)
        mixer.drumsResonance = sni(params.drumsResonance, 0.707f)
        mixer.bassResonance = sni(params.bassResonance, 0.707f)
        mixer.otherResonance = sni(params.otherResonance, 0.707f)
        mixer.vocalsResonance = sni(params.vocalsResonance, 0.707f)

        val panBase = params.otherPan.coerceIn(-1f, 1f)
        val signedPan = (signedCornerPan.coerceIn(-1f, 1f) * (0.35f + cornerAmt * 0.65f) + panBase * 0.3f).coerceIn(-1f, 1f)
        mixer.drumsPan = sni((cornerAmt * 0.2f) * signedPan.sign, 0f)
        mixer.bassPan = 0f
        mixer.otherPan = sni(signedPan, 0f)
        mixer.vocalsPan = sni(signedPan * 0.7f, 0f)

        val trem = (roadRoughness * params.tremoloDepthMax * (0.5f + motion * 0.5f)).coerceIn(0f, 1f)
        mixer.tremoloDepth = sni(trem, 0f)
        mixer.tremoloRate = 5f + roadRoughness * 6f

        val reverb = (params.reverbSendMax * (cornerAmt * 0.6f + brakeReverb * 0.4f)).coerceIn(0f, 1f)
        mixer.reverbWet = sni(reverb, 0f)
        mixer.reverbSize = 0.4f + cornerAmt * 0.4f
        mixer.reverbDecay = 0.5f + brakeReverb * 0.3f

        prevRoadRoughness = roadRoughness
        prevAmbientMood = ambientMood

        return gesture
    }

    private fun ladderTarget(level: Float, enter: Float, full: Float): Float {
        val span = (full - enter).coerceAtLeast(0.05f)
        return ((level - enter) / span).coerceIn(0f, 1f)
    }

    private fun kickArm(armedNs: Long, level: Float, enter: Float, hysteresis: Float, nowNs: Long): Long =
        when {
            level < enter - hysteresis -> 0L
            armedNs == 0L && level >= enter -> nowNs
            else -> armedNs
        }

    private fun kickReady(armedNs: Long, buildOriginNs: Long, nowNs: Long, delayMs: Float): Boolean =
        armedNs != 0L && buildOriginNs != 0L && (nowNs - buildOriginNs) >= (delayMs * 1_000_000f).toLong()

    private fun targetDrumsFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.drumsEnter, params.drumsFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.9f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 1.1f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.8f, t)
        }
    }

    private fun targetBassFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.bassEnter, params.bassFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.85f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 1.2f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.65f, t)
        }
    }

    private fun targetOtherFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.otherEnter, params.otherFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0.5f, 0.95f, t)
            SoundDriveMode.DYNAMIC -> lerp(0.5f, 1.05f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0.8f, 1.4f, t)
        }
    }

    private fun targetVocalsFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.vocalsEnter, params.vocalsFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.85f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 0.95f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.35f, t)
        }
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
            GestureType.ACCEL_BURST -> { gestureDrumsBoost = 0.22f; gestureBassBoost = 0.12f }
            GestureType.BRAKE_HIT -> { gestureDrumsBoost = 0.22f }
            GestureType.CORNER_PEAK -> { gestureDrumsBoost = 0.1f }
            GestureType.BUMP_HIT -> { gestureOtherBoost = 0.12f; gestureBassBoost = 0.06f }
            GestureType.TUNNEL_ENTRY -> { gestureOtherBoost = 0.15f }
        }
    }

    private fun decayGestures() {
        gestureDrumsBoost *= 0.92f
        if (gestureDrumsBoost < 0.01f) gestureDrumsBoost = 0f
        gestureBassBoost *= 0.92f
        if (gestureBassBoost < 0.01f) gestureBassBoost = 0f
        gestureVocalsCut *= 0.92f
        if (gestureVocalsCut > -0.01f) gestureVocalsCut = 0f
        gestureOtherBoost *= 0.92f
        if (gestureOtherBoost < 0.01f) gestureOtherBoost = 0f
    }

    fun resetGestures() {
        gestureDrumsBoost = 0f; gestureBassBoost = 0f
        gestureVocalsCut = 0f; gestureOtherBoost = 0f
        drumsArmedNs = 0L; bassArmedNs = 0L
        otherArmedNs = 0L; vocalsArmedNs = 0L
        buildOriginNs = 0L
    }

    private data class VolumeSet(val drums: Float, val bass: Float, val other: Float, val vocals: Float)
    private companion object {
        fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
        fun sni(v: Float, default: Float = 1f) = if (v.isNaN() || v.isInfinite()) default else v
    }
}

package com.motionsound.sounddrive

import com.motionsound.drive.DrivingState
import com.motionsound.stem.AppLogger
import com.motionsound.stem.BrakeType
import com.motionsound.stem.StemMixer
import kotlin.math.exp
import kotlin.math.sign

class SoundDriveProcessor(private val mixer: StemMixer) {

    private var prevCornerIntensity = 0f
    private var prevRoadRoughness = 0f
    private var prevAmbientMood = 0.5f

    private var gestureDrumsBoost = 0f
    private var gestureBassBoost = 0f
    private var gestureVocalsCut = 0f
    private var gestureOtherBoost = 0f
    private var echoKick = 0f

    @Volatile var manualDrums = 1f
    @Volatile var manualBass = 1f
    @Volatile var manualOther = 1f
    @Volatile var manualVocals = 1f

    private var tunnelRampTimer = 0f
    private var inTunnel = false

    private var longitudinalBias = 0f
    private var cornerSmooth = 0f
    private var accelBurstStreak = 0
    private var brakeHitStreak = 0
    private var lastGestureMs = 0L

    private var drumsEnvelope = 0f
    private var bassEnvelope = 0f
    private var otherEnvelope = 0f
    private var vocalsEnvelope = 0f
    private var drumsArmedNs = 0L
    private var bassArmedNs = 0L
    private var otherArmedNs = 0L
    private var vocalsArmedNs = 0L
    private var otherEnterTimeNs = 0L
    private var vocalsEnterTimeNs = 0L
    private var buildOriginNs = 0L
    private var buildOriginIdleNs = 0L
    private var motionSmooth = 0f
    private var layerLevelSmooth = 0f
    private var lastUpdateNs = 0L
    private var lastDumpMs = 0L

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
            mixer.volumeDrums = manualDrums.coerceIn(0f, 1f); mixer.volumeBass = manualBass.coerceIn(0f, 1f)
            mixer.volumeOther = manualOther.coerceIn(0f, 1f); mixer.volumeVocals = manualVocals.coerceIn(0f, 1f)
            mixer.drumsCutoff = 1f; mixer.drumsResonance = 0.707f; mixer.drumsPan = 0f
            mixer.bassCutoff = 1f; mixer.bassResonance = 0.707f; mixer.bassPan = 0f
            mixer.otherCutoff = 1f; mixer.otherResonance = 0.707f; mixer.otherPan = 0f
            mixer.vocalsCutoff = 1f; mixer.vocalsResonance = 0.707f; mixer.vocalsPan = 0f
            mixer.masterCutoff = 1f; mixer.masterLowCut = 0f
            mixer.reverbWet = 0f
            mixer.echoWet = 0f
            mixer.tremoloDepth = 0f
            mixer.warpDepth = 0f
            mixer.warpRate = 0.5f
            mixer.vocalsGateActive = false
            mixer.vocalsGateTarget = manualVocals.coerceIn(0f, 1f)
            resetGestures()
            return null
        }

        val profile = config.effectiveSensorProfile
        decayGestures()

        var gesture: GestureType? = null
        if (profile.gestureEnabled) {
            gesture = detectGesture(accelIntensity, brakeIntensity, cornerIntensity, verticalJounce, ambientMood, drivingState)
            if (gesture != null) {
                AppLogger.i("SD_GESTURE", gesture.name)
                applyGesture(gesture)
            }
        }

        val i = 0.7f
        val ed = profile.effectDepth

        val nowNs = System.nanoTime()
        val dtSec = if (lastUpdateNs == 0L) 0.016f else ((nowNs - lastUpdateNs) / 1e9f).coerceIn(0f, 0.5f)
        lastUpdateNs = nowNs
        val attackCoef = 1f - exp(-dtSec / (profile.smoothAttackMs / 1000f).coerceAtLeast(0.01f))
        val releaseCoef = 1f - exp(-dtSec / (profile.smoothReleaseMs / 1000f).coerceAtLeast(0.01f))

        val rawMotion = (maxOf(accelIntensity, brakeIntensity, cornerIntensity * 0.6f, speedGate) * i).coerceIn(0f, 1f)
        motionSmooth += (rawMotion - motionSmooth) * if (rawMotion > motionSmooth) attackCoef else releaseCoef
        val motion = motionSmooth.coerceIn(0f, 1f)

        val cornerAmt = (cornerIntensity * i).coerceIn(0f, 1f)
        val cornerReleaseCoef = 1f - exp(-dtSec / 2.5f)
        cornerSmooth += (cornerAmt - cornerSmooth) * if (cornerAmt > cornerSmooth) attackCoef else cornerReleaseCoef

        val regenRetreat = if (brakeType == BrakeType.REGEN) brakeIntensity * 0.5f else 0f
        val brakeK = (params.brakeRetreatMax * ed * (if (regenRetreat > 0f) 1.5f else 1f)).coerceIn(0f, 1f)
        val thrustTarget = accelIntensity - brakeIntensity * brakeK
        val thrustAttackMs = 1000f
        val thrustReleaseMs = 800f
        val thrustCoef = 1f - exp(-dtSec / ((if (thrustTarget > longitudinalBias) thrustAttackMs else thrustReleaseMs) / 1000f).coerceAtLeast(0.01f))
        longitudinalBias += (thrustTarget - longitudinalBias) * thrustCoef
        val launchAssist = accelIntensity * 0.4f * (1f - speedGate)
        var rawLayer = (speedGate + longitudinalBias * params.layerAccelBoost + cornerSmooth * 0.3f + launchAssist).coerceIn(0f, 1f)
        rawLayer = rawLayer.coerceIn(0f, 1f)
        layerLevelSmooth += (rawLayer - layerLevelSmooth) * if (rawLayer > layerLevelSmooth) attackCoef else releaseCoef
        val layerLevel = layerLevelSmooth.coerceIn(0f, 1f)

        drumsArmedNs = kickArm(drumsArmedNs, layerLevel, params.drumsEnter, params.kickHysteresis, nowNs)
        bassArmedNs = kickArm(bassArmedNs, layerLevel, params.bassEnter, params.kickHysteresis, nowNs)
        otherEnterTimeNs = trackEnterTime(otherEnterTimeNs, layerLevel, params.otherEnter, params.kickHysteresis, nowNs)
        otherArmedNs = if (otherEnterTimeNs > 0L && nowNs - otherEnterTimeNs >= SYNTHS_SUSTAIN_NS) otherEnterTimeNs else 0L
        vocalsEnterTimeNs = trackEnterTime(vocalsEnterTimeNs, layerLevel, params.vocalsEnter, params.kickHysteresis, nowNs)
        vocalsArmedNs = if (vocalsEnterTimeNs > 0L && nowNs - vocalsEnterTimeNs >= VOCALS_SUSTAIN_NS) vocalsEnterTimeNs else 0L

        if (layerLevel < params.buildFloor) {
            if (buildOriginIdleNs == 0L) buildOriginIdleNs = nowNs
            else if ((nowNs - buildOriginIdleNs) >= (params.buildLapseMs * 1_000_000f).toLong()) {
                buildOriginNs = 0L
                buildOriginIdleNs = 0L
            }
        } else {
            buildOriginIdleNs = 0L
            if (buildOriginNs == 0L) buildOriginNs = nowNs
        }

        val paceScale = lerp(1f, params.paceScaleMin, accelIntensity.coerceIn(0f, 1f))
        val brakeDip = (1f - ((brakeIntensity - 0.08f) / 0.6f).coerceIn(0f, 1f) * params.brakeEnvDip).coerceIn(0f, 1f)
        val targetDrums = if (kickReady(drumsArmedNs, buildOriginNs, nowNs, params.drumsDelayMs * paceScale)) targetDrumsFor(config.mode, params, layerLevel) * brakeDip else 0f
        val targetBass = if (kickReady(bassArmedNs, buildOriginNs, nowNs, params.bassDelayMs * paceScale)) targetBassFor(config.mode, params, layerLevel) * brakeDip else 0f
        val targetOther = if (kickReady(otherArmedNs, buildOriginNs, nowNs, params.otherDelayMs * paceScale)) targetOtherFor(config.mode, params, layerLevel) * brakeDip else 0f
        val targetVocals = if (kickReady(vocalsArmedNs, buildOriginNs, nowNs, params.vocalsDelayMs * paceScale)) targetVocalsFor(config.mode, params, layerLevel) * brakeDip else 0f

        drumsEnvelope += (targetDrums - drumsEnvelope) * if (targetDrums > drumsEnvelope) attackCoef else releaseCoef
        bassEnvelope += (targetBass - bassEnvelope) * if (targetBass > bassEnvelope) attackCoef else releaseCoef
        otherEnvelope += (targetOther - otherEnvelope) * if (targetOther > otherEnvelope) attackCoef * 0.6f else releaseCoef
        vocalsEnvelope += (targetVocals - vocalsEnvelope) * if (targetVocals > vocalsEnvelope) attackCoef * 0.35f else releaseCoef * 0.5f

        val brakeReverb = if (brakeType == BrakeType.FRICTION) brakeIntensity * 0.8f else 0f
        val nightCut = (1f - ambientMood) * 0.15f

        updateTunnelRamp(ambientMood)

        val idleBed = 0.37f
        val md = manualDrums.coerceIn(0f, 1f); val mb = manualBass.coerceIn(0f, 1f)
        val mo = manualOther.coerceIn(0f, 1f); val mv = manualVocals.coerceIn(0f, 1f)
        val drumsScale = params.idleDrumsScale + (1f - params.idleDrumsScale) * motion
        mixer.volumeDrums = sni((params.drumsFloor * drumsScale * (idleBed + (1f - idleBed) * motion) + drumsEnvelope + gestureDrumsBoost).coerceIn(0f, 1f) * md, 1f)
        mixer.volumeBass = sni((params.bassFloor * (idleBed + (1f - idleBed) * motion) + bassEnvelope + gestureBassBoost).coerceIn(0f, 1f) * mb, 1f)
        mixer.volumeOther = sni((params.otherFloor * motion + otherEnvelope * (1f + tunnelRampTimer) + gestureOtherBoost).coerceIn(0f, 1f) * mo, 1f)
        val vocalsAuto = (params.vocalsFloor + vocalsEnvelope * (1f - nightCut) + gestureVocalsCut).coerceIn(0f, 1f) * mv
        mixer.vocalsGateActive = true
        mixer.vocalsGateTarget = sni(vocalsAuto, 1f)
        mixer.volumeVocals = sni(vocalsAuto, 1f)

        val idleMuffle = (1f - motion.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val masterTarget = params.masterCutoff * (1f - idleMuffle * 0.3f * ed)
        mixer.masterCutoff = sni(masterTarget, 1f)
        mixer.masterLowCut = sni(params.masterLowCut + idleMuffle * 0.004f, 0f)

        mixer.drumsCutoff = sni(lerp(params.drumsCutoff * 0.75f, params.drumsCutoff, motion), 1f)
        mixer.bassCutoff = sni(lerp(params.bassCutoff * 0.75f, params.bassCutoff, motion), 1f)
        mixer.otherCutoff = sni(lerp(params.otherCutoff * 0.72f, params.otherCutoff, motion * 0.9f + cornerAmt * 0.1f), 1f)
        mixer.vocalsCutoff = sni(lerp(params.vocalsCutoff * 0.72f, params.vocalsCutoff, motion * 0.95f), 1f)
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
        mixer.tremoloRate = (5f + roadRoughness * 6f - brakeIntensity * 8f).coerceAtLeast(0.5f)

        val warpDepth = (params.warpDepthMax * cornerSmooth + (1f - motion).coerceIn(0f, 1f) * 0.12f * ed).coerceIn(0f, 1f)
        mixer.warpDepth = sni(warpDepth, 0f)
        mixer.warpRate = 0.5f + cornerSmooth * params.warpRateMax

        val idleReverb = (1f - motion).coerceIn(0f, 1f) * params.idleReverbMax * ed
        val reverb = (params.reverbSendMax * (cornerSmooth * 0.6f + brakeReverb * 0.4f) + idleReverb).coerceIn(0f, 1f)
        mixer.reverbWet = sni(reverb, 0f)
        mixer.reverbSize = 0.4f + cornerSmooth * 0.4f + (1f - motion).coerceIn(0f, 1f) * 0.5f
        mixer.reverbDecay = (0.5f + brakeReverb * 0.3f - (1f - motion).coerceIn(0f, 1f) * 0.25f).coerceIn(0f, 1f)

        val idleEcho = (1f - motion).coerceIn(0f, 1f) * params.idleEchoMax * ed
        val echo = (params.echoSendMax * (cornerSmooth * 0.5f + brakeReverb * 0.6f) + echoKick + idleEcho).coerceIn(0f, 0.6f)
        mixer.echoWet = sni(echo, 0f)

        prevRoadRoughness = roadRoughness
        prevAmbientMood = ambientMood

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDumpMs >= 1000L) {
            lastDumpMs = nowMs
            AppLogger.i(
                "SD_MIX",
                "mode=${config.mode} motion=${"%.2f".format(motion)} layer=${"%.2f".format(layerLevel)} " +
                    "thrust=${"%.2f".format(longitudinalBias)} drums=${"%.2f".format(mixer.volumeDrums)} " +
                    "bass=${"%.2f".format(mixer.volumeBass)} other=${"%.2f".format(mixer.volumeOther)} " +
                    "vocals=${"%.2f".format(mixer.volumeVocals)} warp=${"%.2f".format(warpDepth)} " +
                    "reverb=${"%.2f".format(reverb)} size=${"%.2f".format(mixer.reverbSize)} " +
                    "trem=${"%.2f".format(mixer.tremoloDepth)} tremRate=${"%.2f".format(mixer.tremoloRate)} " +
                    "kick=[${if (drumsArmedNs > 0) "D" else "-"}${if (bassArmedNs > 0) "B" else "-"}${if (otherArmedNs > 0) "O" else "-"}${if (vocalsArmedNs > 0) "V" else "-"}]"
            )
        }

        return gesture
    }

    private fun ladderTarget(level: Float, enter: Float, full: Float): Float {
        val span = (full - enter).coerceAtLeast(0.05f)
        return ((level - enter) / span).coerceIn(0f, 1f)
    }

    private fun kickArm(armedNs: Long, level: Float, enter: Float, hysteresis: Float, nowNs: Long): Long =
        when {
            level < enter - hysteresis -> -(nowNs + ARM_HOLD_NS)
            armedNs <= 0L && level >= enter && nowNs >= -armedNs -> nowNs
            else -> armedNs
        }

    private fun kickReady(armedNs: Long, buildOriginNs: Long, nowNs: Long, delayMs: Float): Boolean =
        armedNs > 0L && buildOriginNs != 0L && (nowNs - buildOriginNs) >= (delayMs * 1_000_000f).toLong()

    private fun trackEnterTime(enterTimeNs: Long, level: Float, enter: Float, hysteresis: Float, nowNs: Long): Long =
        when {
            level < enter - hysteresis -> 0L
            level < enter -> enterTimeNs
            enterTimeNs == 0L -> nowNs
            else -> enterTimeNs
        }

    private fun targetDrumsFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.drumsEnter, params.drumsFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.75f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 0.85f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.68f, t)
        }
    }

    private fun targetBassFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.bassEnter, params.bassFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.7f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 0.95f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.6f, t)
        }
    }

    private fun targetOtherFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.otherEnter, params.otherFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0.45f, 0.9f, t)
            SoundDriveMode.DYNAMIC -> lerp(0.45f, 1f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0.4f, 0.85f, t)
        }
    }

    private fun targetVocalsFor(mode: SoundDriveMode, params: SoundDriveParams, level: Float): Float {
        val t = ladderTarget(level, params.vocalsEnter, params.vocalsFull)
        return when (mode) {
            SoundDriveMode.BALANCED -> lerp(0f, 0.8f, t)
            SoundDriveMode.DYNAMIC -> lerp(0f, 0.95f, t)
            SoundDriveMode.IMMERSIVE -> lerp(0f, 0.55f, t)
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

        if (accelIntensity > 0.35f && drivingState == DrivingState.ACCELERATING) accelBurstStreak++ else accelBurstStreak = 0
        if (brakeIntensity > 0.35f && drivingState == DrivingState.DECELERATING) brakeHitStreak++ else brakeHitStreak = 0

        val result: GestureType? = when {
            verticalJounce > 0.35f && verticalJounce > prevRoadRoughness * 3f -> GestureType.BUMP_HIT
            accelBurstStreak >= 2 -> {
                accelBurstStreak = 0
                GestureType.ACCEL_BURST
            }
            brakeHitStreak >= 2 -> {
                brakeHitStreak = 0
                GestureType.BRAKE_HIT
            }
            cornerIntensity > 0.45f && cornerIntensity > prevCornerIntensity * 1.5f
                && drivingState == DrivingState.CORNERING -> GestureType.CORNER_PEAK
            else -> null
        }
        prevCornerIntensity = cornerIntensity
        if (result == null) return null
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastGestureMs < 450L) return null
        lastGestureMs = nowMs
        return result
    }

    private fun applyGesture(gesture: GestureType) {
        when (gesture) {
            GestureType.ACCEL_BURST -> { gestureBassBoost = 0.35f; gestureDrumsBoost = 0.15f; echoKick = 0.15f }
            GestureType.BRAKE_HIT -> { gestureDrumsBoost = 0.22f; echoKick = 0.3f }
            GestureType.CORNER_PEAK -> { gestureOtherBoost = 0.2f; echoKick = 0.15f }
            GestureType.BUMP_HIT -> { gestureOtherBoost = 0.12f; gestureBassBoost = 0.06f }
            GestureType.TUNNEL_ENTRY -> { gestureOtherBoost = 0.15f; echoKick = 0.25f }
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
        echoKick *= 0.85f
        if (echoKick < 0.01f) echoKick = 0f
    }

    fun resetGestures() {
        gestureDrumsBoost = 0f; gestureBassBoost = 0f
        gestureVocalsCut = 0f; gestureOtherBoost = 0f
        echoKick = 0f
        accelBurstStreak = 0
        brakeHitStreak = 0
        drumsArmedNs = 0L; bassArmedNs = 0L
        otherArmedNs = 0L; vocalsArmedNs = 0L
        otherEnterTimeNs = 0L; vocalsEnterTimeNs = 0L
        buildOriginNs = 0L; buildOriginIdleNs = 0L
        longitudinalBias = 0f
        cornerSmooth = 0f
    }

    private data class VolumeSet(val drums: Float, val bass: Float, val other: Float, val vocals: Float)
    private companion object {
        const val ARM_HOLD_NS = 1_500_000_000L
        const val SYNTHS_SUSTAIN_NS = 1_500_000_000L
        const val VOCALS_SUSTAIN_NS = 2_500_000_000L
        fun lerp(a: Float, b: Float, t: Float) = a + t.coerceIn(0f, 1f) * (b - a)
        fun sni(v: Float, default: Float = 1f) = if (v.isNaN() || v.isInfinite()) default else v
    }
}

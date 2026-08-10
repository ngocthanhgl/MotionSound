package com.motionsound.stem

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.motionsound.sounddrive.BiquadFilter
import com.motionsound.sounddrive.Echo
import com.motionsound.sounddrive.Reverb
import com.motionsound.sounddrive.StemFxChain
import com.motionsound.sounddrive.Tremolo
import com.motionsound.sounddrive.Warp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

class StemMixer {

    @Volatile var volumeDrums: Float = 1.0f
    @Volatile var volumeBass: Float = 1.0f
    @Volatile var volumeOther: Float = 1.0f
    @Volatile var volumeVocals: Float = 1.0f
    @Volatile var masterVolume: Float = 1.0f

    @Volatile var drumsCutoff: Float = 1f
    @Volatile var drumsResonance: Float = 0.707f
    @Volatile var drumsPan: Float = 0f
    @Volatile var bassCutoff: Float = 1f
    @Volatile var bassResonance: Float = 0.707f
    @Volatile var bassPan: Float = 0f
    @Volatile var otherCutoff: Float = 1f
    @Volatile var otherResonance: Float = 0.707f
    @Volatile var otherPan: Float = 0f
    @Volatile var vocalsCutoff: Float = 1f
    @Volatile var vocalsResonance: Float = 0.707f
    @Volatile var vocalsPan: Float = 0f
    @Volatile var masterCutoff: Float = 1f
    @Volatile var masterLowCut: Float = 0f

    @Volatile var reverbWet: Float = 0f
    @Volatile var reverbSize: Float = 0.5f
    @Volatile var reverbDecay: Float = 0.5f
    @Volatile var tremoloDepth: Float = 0f
    @Volatile var tremoloRate: Float = 4f
    @Volatile var echoWet: Float = 0f
    @Volatile var warpDepth: Float = 0f
    @Volatile var warpRate: Float = 0.5f

    @Volatile var vocalsGateActive: Boolean = false
    @Volatile var vocalsGateTarget: Float = 0f

    @Volatile private var beatGate: IntArray = IntArray(0)
    @Volatile private var sectionEnergy: FloatArray = FloatArray(0)
    @Volatile private var sectionBlockFrames: Int = 32768
    private var vgCur = 0f
    private var vgRamping = false
    private var vgRampStart = 0
    private var vgRampFrom = 0f
    private var vgRampTo = 0f
    private var vgBeatIdx = 0
    private val vgRampFrames = 1764
    private var secCur = 1f
    private var wetCur = 0f
    private var echoWetCur = 0f
    private var playbackStartFrame = 0
    private val fadeInFrames = 512
    private val fadeOutFrames = 512

    private val drumsFx = StemFxChain()
    private val bassFx = StemFxChain()
    private val otherFx = StemFxChain()
    private val vocalsFx = StemFxChain()
    private val masterLpf = BiquadFilter()
    private val masterHpf = BiquadFilter()
    private val reverb = Reverb()
    private val tremolo = Tremolo()
    private val echo = Echo()
    private val warp = Warp()
    private val vocalsWarp = Warp()

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private val playbackHeadFrame = AtomicInteger(0)
    @Volatile
    var onTrackEnded: (() -> Unit)? = null
    @Volatile private var released = false

    private val bufferFrames = 8192

    fun prepare() {
        val bufSize = AudioTrack.getMinBufferSize(
            StemConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(bufferFrames * StemConfig.NUM_CHANNELS * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(StemConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        released = false
        AppLogger.i("StemMixer", "Prepared bufSize=$bufSize")
    }

    fun play(stems: StemResult, scope: CoroutineScope, startFrame: Int = 0) {
        AppLogger.event("StemMixer", "PLAY", "startFrame=$startFrame total=${stems.frameCount}")
        stop()
        isPlaying.set(true)
        playbackHeadFrame.set(startFrame)
        playbackStartFrame = startFrame
        audioTrack?.play()

        playJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val mixBuf = FloatArray(bufferFrames * StemConfig.NUM_CHANNELS)
            val totalFrames = stems.frameCount

            var frame = startFrame
            var chunkIdx = 0
            while (isActive && !released && isPlaying.get() && frame < totalFrames) {
                val framesToWrite = min(bufferFrames, totalFrames - frame)
                mix(stems, frame, framesToWrite, mixBuf)
                val track = audioTrack
                if (track == null || released) break
                try {
                    track.write(mixBuf, 0, framesToWrite * StemConfig.NUM_CHANNELS, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    break
                }
                frame += framesToWrite
                playbackHeadFrame.set(frame)
                if (++chunkIdx % 50 == 0) {
                    track.getPlaybackHeadPosition()
                    val uc = track.underrunCount
                    if (uc > 0) AppLogger.i("StemMixer", "underrun=$uc chunk=$chunkIdx")
                }
            }
            isPlaying.set(false)
            if (frame >= totalFrames) {
                val cb = onTrackEnded
                if (cb != null) {
                    AppLogger.event("StemMixer", "TRACK_ENDED")
                    cb.invoke()
                }
            }
        }
    }

    fun pause() {
        AppLogger.event("StemMixer", "PAUSE")
        isPlaying.set(false)
        audioTrack?.pause()
        playJob?.cancel()
    }

    fun stop() {
        AppLogger.event("StemMixer", "STOP")
        isPlaying.set(false)
        playJob?.cancel()
        audioTrack?.stop()
        audioTrack?.flush()
        playbackHeadFrame.set(0)
        echo.reset()
        warp.reset()
        vocalsWarp.reset()
    }

    fun seekToFrame(frame: Int, stems: StemResult, scope: CoroutineScope) {
        AppLogger.event("StemMixer", "SEEK", "frame=$frame")
        stop()
        play(stems, scope, startFrame = frame)
    }

    fun getPlaybackPositionSeconds(): Float =
        playbackHeadFrame.get().toFloat() / StemConfig.SAMPLE_RATE

    fun isPlaying(): Boolean = isPlaying.get()

    fun setBeatGrid(analysis: StemAnalysis) {
        beatGate = analysis.beatFrames
        sectionEnergy = analysis.sectionEnergy
        sectionBlockFrames = analysis.sectionBlockFrames.coerceAtLeast(1)
        vgBeatIdx = 0
        vgCur = vocalsGateTarget
        vgRamping = false
        secCur = 1f
    }

    fun release() {
        AppLogger.event("StemMixer", "RELEASE")
        released = true
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun accumulateWarped(
        chain: StemFxChain,
        stem: java.nio.FloatBuffer,
        startFrame: Int,
        count: Int,
        vol: Float,
        warpFx: Warp,
        out: FloatArray
    ) {
        val g = FloatArray(2)

        for (f in 0 until count) {
            val stemIdx = (startFrame + f) * 2
            val outIdx = f * 2
            chain.smoothedGains(vol, g)
            val l = warpFx.processLeft(chain.filter.processLeft(stem.get(stemIdx)))
            val r = warpFx.processRight(chain.filter.processRight(stem.get(stemIdx + 1)))
            out[outIdx] += l * g[0]
            out[outIdx + 1] += r * g[1]
        }
    }

    private fun accumulateVocalsGated(
        stem: java.nio.FloatBuffer,
        startFrame: Int,
        count: Int,
        vol: Float,
        warpFx: Warp?,
        out: FloatArray
    ) {
        val target = vol.coerceIn(0f, 1f)
        val sec = sectionEnergy
        val secBlock = sectionBlockFrames
        val rampFrames = vgRampFrames

        val angle = (vocalsPan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
        val gainL = cos(angle)
        val gainR = sin(angle)

        for (f in 0 until count) {
            val pos = startFrame + f

            if (!vgRamping && kotlin.math.abs(vgCur - target) > 0.02f) {
                while (vgBeatIdx < beatGate.size && beatGate[vgBeatIdx] < pos) vgBeatIdx++
                if (vgBeatIdx >= beatGate.size) {
                    vgCur = target
                } else {
                    vgRampStart = beatGate[vgBeatIdx]
                    vgRampFrom = vgCur
                    vgRampTo = target
                    vgRamping = true
                }
            }
            if (vgRamping && pos >= vgRampStart) {
                val t = ((pos - vgRampStart).toFloat() / rampFrames).coerceIn(0f, 1f)
                vgCur = vgRampFrom + (vgRampTo - vgRampFrom) * t
                if (t >= 1f) vgRamping = false
            }

            var g = vgCur
            if (sec.isNotEmpty()) {
                val si = (pos / secBlock).coerceIn(0, sec.size - 1)
                val secTarget = 1f + 0.9f * sec[si]
                secCur += (secTarget - secCur) * 0.02f
                g *= secCur
            }
            g = g.coerceIn(0f, 1.5f)

            val stemIdx = (startFrame + f) * 2
            val outIdx = f * 2
            var l = vocalsFx.filter.processLeft(stem.get(stemIdx))
            var r = vocalsFx.filter.processRight(stem.get(stemIdx + 1))
            if (warpFx != null) {
                l = warpFx.processLeft(l)
                r = warpFx.processRight(r)
            }
            out[outIdx] += l * gainL * g
            out[outIdx + 1] += r * gainR * g
        }
    }

    private fun mix(stems: StemResult, startFrame: Int, count: Int, out: FloatArray) {
        val vD = (volumeDrums * masterVolume).coerceIn(0f, 1f)
        val vB = (volumeBass * masterVolume).coerceIn(0f, 1f)
        val vO = (volumeOther * masterVolume).coerceIn(0f, 1f)
        val vV = if (vocalsGateActive) vocalsGateTarget else (volumeVocals * masterVolume).coerceIn(0f, 1f)

        out.fill(0f, 0, count * 2)

        drumsFx.pan = drumsPan
        drumsFx.filter.lowPass(drumsCutoff, drumsResonance)
        drumsFx.accumulate(stems.drums, startFrame, count, vD, out)

        bassFx.pan = bassPan
        bassFx.filter.lowPass(bassCutoff, bassResonance)
        bassFx.accumulate(stems.bass, startFrame, count, vB, out)

        otherFx.pan = otherPan
        otherFx.filter.lowPass(otherCutoff, otherResonance)
        val wD = warpDepth.coerceIn(0f, 1f)
        if (wD > 0.001f) {
            warp.configure(warpRate, wD * 4f)
            accumulateWarped(otherFx, stems.other, startFrame, count, vO, warp, out)
        } else {
            otherFx.accumulate(stems.other, startFrame, count, vO, out)
        }

        vocalsFx.pan = vocalsPan
        vocalsFx.filter.lowPass(vocalsCutoff, vocalsResonance)
        val wV = (warpDepth * 0.5f).coerceIn(0f, 1f)
        if (vocalsGateActive && beatGate.isNotEmpty()) {
            if (wV > 0.001f) {
                vocalsWarp.configure(warpRate, wV * 4f)
                accumulateVocalsGated(stems.vocals, startFrame, count, vV, vocalsWarp, out)
            } else {
                accumulateVocalsGated(stems.vocals, startFrame, count, vV, null, out)
            }
        } else {
            if (wV > 0.001f) {
                vocalsWarp.configure(warpRate, wV * 4f)
                accumulateWarped(vocalsFx, stems.vocals, startFrame, count, vV, vocalsWarp, out)
            } else {
                vocalsFx.accumulate(stems.vocals, startFrame, count, vV, out)
            }
        }

        masterLpf.lowPass(masterCutoff, 0.707f)
        masterHpf.highPass(masterLowCut.coerceAtLeast(0f), 0.707f)
        reverb.configure(reverbSize, reverbDecay)
        tremolo.configure(tremoloRate, tremoloDepth)
        val wetTarget = reverbWet.coerceIn(0f, 1f)

        for (f in 0 until count) {
            val idx = f * 2
            wetCur += (wetTarget - wetCur) * 0.12f
            val wet = wetCur.coerceIn(0f, 1f)
            val dry = 1f - wet
            val mL = masterHpf.processLeft(masterLpf.processLeft(out[idx]))
            val mR = masterHpf.processRight(masterLpf.processRight(out[idx + 1]))
            if (wet > 0.001f) {
                out[idx] = dry * mL + wet * reverb.processLeft(mL)
                out[idx + 1] = dry * mR + wet * reverb.processRight(mR)
            } else {
                out[idx] = mL
                out[idx + 1] = mR
            }
            echoWetCur += (echoWet.coerceIn(0f, 0.6f) - echoWetCur) * 0.12f
            if (echoWetCur > 0.001f) {
                out[idx] += echoWetCur * echo.processLeft(mL)
                out[idx + 1] += echoWetCur * echo.processRight(mR)
            } else {
                echo.processLeft(mL)
                echo.processRight(mR)
            }
        }

        if (tremoloDepth > 0.001f) {
            for (f in 0 until count) {
                val idx = f * 2
                out[idx] = tremolo.process(out[idx])
                out[idx + 1] = tremolo.process(out[idx + 1])
            }
        }

        val headroom = 0.75f
        for (f in 0 until count) {
            val idx = f * 2
            out[idx] = tanh(out[idx] * 0.8f) / 0.8f * headroom
            out[idx + 1] = tanh(out[idx + 1] * 0.8f) / 0.8f * headroom
        }

        val totalFrames = stems.frameCount
        for (f in 0 until count) {
            val pos = startFrame + f
            val sinceStart = pos - playbackStartFrame
            val remaining = totalFrames - pos
            if (sinceStart >= fadeInFrames && remaining > fadeOutFrames) continue
            var env = 1f
            if (sinceStart < fadeInFrames) env = (sinceStart + 1).toFloat() / fadeInFrames.toFloat()
            if (remaining <= fadeOutFrames) env = min(env, remaining.toFloat() / fadeOutFrames.toFloat())
            val idx = f * 2
            out[idx] *= env
            out[idx + 1] *= env
        }
    }
}

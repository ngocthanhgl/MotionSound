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
import kotlin.math.exp
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

    /**
     * Playback tempo scale, 1.0 = normal speed. Sound Drive-style tempo
     * modulation: spirited driving pushes it above 1, coasting/stopping
     * pulls it below. Applied as a fractional source-read rate in the mix
     * loop (linear interpolation, beat grid stays in source-frame domain).
     */
    @Volatile var tempoScale: Float = 1f

    /**
     * Depth of the beat-synced "revving pump" (0 = off). Adds an exponential
     * swell to drums + bass starting at every detected beat — the Sound Drive
     * accelerator behaviour ("a revving sound pulsates to the beat").
     */
    @Volatile var beatPumpDepth: Float = 0f

    @Volatile private var beatGate: IntArray = IntArray(0)
    @Volatile private var sectionEnergy: FloatArray = FloatArray(0)
    @Volatile private var sectionBlockFrames: Int = 32768
    @Volatile var beatsSeen: Long = 0L
    @Volatile private var vgScanIdx = 0
    @Volatile private var vgCur = 0f
    @Volatile private var vgRamping = false
    @Volatile private var vgRampStart = 0f
    @Volatile private var vgRampFrom = 0f
    @Volatile private var vgRampTo = 0f
    @Volatile private var vgBeatIdx = 0
    private var pumpBeatIdx = 0
    private var pumpLastBeat = -1f
    @Volatile var vocalsGateRampFrames: Int = 1764
    @Volatile private var secCur = 1f
    private var wetCur = 0f
    private var echoWetCur = 0f
    @Volatile private var playbackStartFrame = 0
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

    @Volatile private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val writeMutex = kotlinx.coroutines.sync.Mutex()
    private val isPlaying = AtomicBoolean(false)
    private val playbackHeadFrame = AtomicInteger(0)
    @Volatile private var endedNaturally = false
    @Volatile
    var onTrackEnded: (() -> Unit)? = null
    @Volatile private var released = false

    private val bufferFrames = 8192

    fun prepare() {
        val minBuf = AudioTrack.getMinBufferSize(
            StemConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        require(minBuf > 0) { "AudioTrack.getMinBufferSize failed: $minBuf" }
        val bufSize = maxOf(minBuf, bufferFrames * StemConfig.NUM_CHANNELS * 4)

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
        check(audioTrack?.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
        released = false
    }

    fun play(stems: StemResult, scope: CoroutineScope, startFrame: Int = 0) {
        stop()
        val track = audioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) return
        isPlaying.set(true)
        playbackHeadFrame.set(startFrame)
        playbackStartFrame = startFrame
        vgScanIdx = 0
        vgBeatIdx = 0
        vgRamping = false
        pumpBeatIdx = 0
        pumpLastBeat = -1f

        playJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val mixBuf = FloatArray(bufferFrames * StemConfig.NUM_CHANNELS)
            val totalFrames = stems.frameCount

            // Fractional source position: advances by framesToWrite * tempoScale,
            // so driving-driven tempo changes stretch/compress the timeline while
            // the AudioTrack keeps consuming exactly [framesToWrite] output frames.
            var posF = startFrame.toFloat()
            while (isActive && !released && isPlaying.get() && posF < totalFrames - 1f) {
                val framesToWrite = min(bufferFrames, (totalFrames - posF).toInt().coerceAtLeast(1))
                writeMutex.lock()
                var aborted = false
                try {
                    if (!isPlaying.get() || released) {
                        aborted = true
                    } else {
                        val ok = mixAndWrite(stems, posF, framesToWrite, mixBuf)
                        if (!ok) {
                            aborted = true
                        } else {
                            posF += framesToWrite * tempoScale
                            playbackHeadFrame.set(posF.toInt().coerceAtMost(totalFrames))
                        }
                    }
                } finally {
                    writeMutex.unlock()
                }
                if (aborted) break
            }
            isPlaying.set(false)
            if (posF >= totalFrames - 1f) {
                endedNaturally = true
                val cb = onTrackEnded
                if (cb != null) {
                    cb.invoke()
                }
            }
        }
    }

    private fun mixAndWrite(stems: StemResult, startF: Float, count: Int, mixBuf: FloatArray): Boolean {
        mix(stems, startF, count, mixBuf)
        val track = audioTrack ?: return false
        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                try { track.play() } catch (_: IllegalStateException) {}
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try { track.play() } catch (_: IllegalStateException) { return false }
            }
            var offset = 0
            while (offset < count) {
                val w = track.write(
                    mixBuf, offset * StemConfig.NUM_CHANNELS, (count - offset) * StemConfig.NUM_CHANNELS,
                    AudioTrack.WRITE_BLOCKING
                )
                if (w <= 0) return offset > 0
                offset += w / StemConfig.NUM_CHANNELS
            }
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun pause() {
        isPlaying.set(false)
        audioTrack?.pause()
        playJob?.cancel()
    }

    fun stop() {
        isPlaying.set(false)
        playJob?.cancel()
        audioTrack?.let { t ->
            try {
                t.stop()
                if (!endedNaturally) t.flush()
            } catch (_: IllegalStateException) {}
        }
        endedNaturally = false
        playbackHeadFrame.set(0)
        echo.reset()
        warp.reset()
        vocalsWarp.reset()
        reverb.reset()
        tremolo.reset()
        pumpBeatIdx = 0
        pumpLastBeat = -1f
    }

    fun seekToFrame(frame: Int, stems: StemResult, scope: CoroutineScope) {
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
        vgScanIdx = 0
        vgCur = vocalsGateTarget
        vgRamping = false
        secCur = 1f
        pumpBeatIdx = 0
        pumpLastBeat = -1f
    }

    fun release() {
        released = true
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun accumulateWarped(
        chain: StemFxChain,
        stem: java.nio.FloatBuffer,
        startF: Float,
        count: Int,
        vol: Float,
        warpFx: Warp,
        out: FloatArray
    ) {
        val g = FloatArray(2)
        val last = stem.capacity() / 2 - 1
        var posF = startF

        for (f in 0 until count) {
            val i0 = posF.toInt().coerceIn(0, last)
            val i1 = min(i0 + 1, last)
            val frac = (posF - i0).coerceIn(0f, 1f)
            val base = i0 * 2
            val base1 = i1 * 2
            val outIdx = f * 2
            chain.smoothedGains(vol, g)
            val l = warpFx.processLeft(chain.filter.processLeft(
                stem.get(base) + (stem.get(base1) - stem.get(base)) * frac
            ))
            val r = warpFx.processRight(chain.filter.processRight(
                stem.get(base + 1) + (stem.get(base1 + 1) - stem.get(base + 1)) * frac
            ))
            out[outIdx] += l * g[0]
            out[outIdx + 1] += r * g[1]
            posF += tempoScale
        }
    }

    private fun accumulateVocalsGated(
        stem: java.nio.FloatBuffer,
        startF: Float,
        count: Int,
        vol: Float,
        warpFx: Warp?,
        out: FloatArray
    ) {
        val target = vol.coerceIn(0f, 1f)
        val sec = sectionEnergy
        val secBlock = sectionBlockFrames
        val rampFrames = vocalsGateRampFrames.coerceAtLeast(1)
        val gate = beatGate
        val last = stem.capacity() / 2 - 1
        val tempo = tempoScale

        val angle = (vocalsPan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
        val gainL = cos(angle)
        val gainR = sin(angle)

        val prevScanIdx = vgScanIdx
        val blockEnd = startF + count * tempo
        while (vgScanIdx < gate.size && gate[vgScanIdx] < blockEnd) vgScanIdx++
        if (vgScanIdx > prevScanIdx) beatsSeen += (vgScanIdx - prevScanIdx).toLong()

        var posF = startF
        for (f in 0 until count) {
            val pos = posF

            if (!vgRamping && kotlin.math.abs(vgCur - target) > 0.02f) {
                if (target < vgCur) {
                    vgRampStart = pos
                    vgRampFrom = vgCur
                    vgRampTo = target
                    vgRamping = true
                } else {
                    while (vgBeatIdx < gate.size && gate[vgBeatIdx] < pos) vgBeatIdx++
                    if (vgBeatIdx >= gate.size) {
                        vgRampStart = pos
                        vgRampFrom = vgCur
                        vgRampTo = target
                        vgRamping = true
                    } else {
                        vgRampStart = gate[vgBeatIdx].toFloat()
                        vgRampFrom = vgCur
                        vgRampTo = target
                        vgRamping = true
                    }
                }
            }
            if (vgRamping && pos >= vgRampStart) {
                val t = ((pos - vgRampStart) / rampFrames).coerceIn(0f, 1f)
                vgCur = vgRampFrom + (vgRampTo - vgRampFrom) * t
                if (t >= 1f) vgRamping = false
            }

            var g = vgCur
            if (sec.isNotEmpty()) {
                val si = (pos / secBlock).toInt().coerceIn(0, sec.size - 1)
                val secTarget = 1f + 0.9f * sec[si]
                secCur += (secTarget - secCur) * 0.02f
                g *= secCur
            }
            g = g.coerceIn(0f, 1.5f)

            val i0 = posF.toInt().coerceIn(0, last)
            val i1 = min(i0 + 1, last)
            val frac = (posF - i0).coerceIn(0f, 1f)
            val base = i0 * 2
            val base1 = i1 * 2
            val outIdx = f * 2
            var l = vocalsFx.filter.processLeft(
                stem.get(base) + (stem.get(base1) - stem.get(base)) * frac
            )
            var r = vocalsFx.filter.processRight(
                stem.get(base + 1) + (stem.get(base1 + 1) - stem.get(base + 1)) * frac
            )
            if (warpFx != null) {
                l = warpFx.processLeft(l)
                r = warpFx.processRight(r)
            }
            out[outIdx] += l * gainL * g
            out[outIdx + 1] += r * gainR * g
            posF += tempo
        }
    }

    /**
     * Beat-synced pump envelope for drums/bass (Sound Drive accelerator
     * behaviour). At every detected beat the envelope jumps to
     * 1 + depth*0.6 and decays exponentially over ~120 ms, producing the
     * "revving sound pulsates to the beat" swell. Without a beat grid it
     * falls back to a fixed 2 Hz pulse phase-locked to the song start.
     */
    private fun computePumpEnv(startF: Float, count: Int): FloatArray {
        val depth = beatPumpDepth.coerceIn(0f, 1f)
        val env = FloatArray(count)
        val gate = beatGate
        val decay = (0.12f * StemConfig.SAMPLE_RATE).coerceAtLeast(1f)
        val period = 0.5f * StemConfig.SAMPLE_RATE
        val tempo = tempoScale.coerceIn(0.5f, 2f)
        var posF = startF
        for (f in 0 until count) {
            val pos = posF
            val t: Float = if (gate.isNotEmpty()) {
                while (pumpBeatIdx < gate.size && gate[pumpBeatIdx] < pos) {
                    pumpLastBeat = gate[pumpBeatIdx].toFloat()
                    pumpBeatIdx++
                }
                if (pumpLastBeat >= 0f) (pos - pumpLastBeat).coerceAtLeast(0f) else period
            } else {
                pos % period
            }
            env[f] = 1f + depth * 0.6f * exp(-t / decay)
            posF += tempo
        }
        return env
    }

    private fun mix(stems: StemResult, startF: Float, count: Int, out: FloatArray) {
        val vD = (volumeDrums * masterVolume).coerceIn(0f, 1f)
        val vB = (volumeBass * masterVolume).coerceIn(0f, 1f)
        val vO = (volumeOther * masterVolume).coerceIn(0f, 1f)
        val vV = if (vocalsGateActive) vocalsGateTarget else (volumeVocals * masterVolume).coerceIn(0f, 1f)

        out.fill(0f, 0, count * 2)

        drumsFx.pan = drumsPan
        drumsFx.filter.lowPass(drumsCutoff, drumsResonance)

        bassFx.pan = bassPan
        bassFx.filter.lowPass(bassCutoff, bassResonance)

        val pumpDepth = beatPumpDepth
        val pumpEnv = if (pumpDepth > 0.001f) computePumpEnv(startF, count) else null
        drumsFx.accumulate(stems.drums, startF, count, vD, out, tempoScale, pumpEnv)
        bassFx.accumulate(stems.bass, startF, count, vB, out, tempoScale, pumpEnv)

        otherFx.pan = otherPan
        otherFx.filter.lowPass(otherCutoff, otherResonance)
        val wD = warpDepth.coerceIn(0f, 1f)
        if (wD > 0.001f) {
            warp.configure(warpRate, wD * 4f)
            accumulateWarped(otherFx, stems.other, startF, count, vO, warp, out)
        } else {
            otherFx.accumulate(stems.other, startF, count, vO, out)
        }

        vocalsFx.pan = vocalsPan
        vocalsFx.filter.lowPass(vocalsCutoff, vocalsResonance)
        val wV = (warpDepth * 0.5f).coerceIn(0f, 1f)
        if (vocalsGateActive && beatGate.isNotEmpty()) {
            if (wV > 0.001f) {
                vocalsWarp.configure(warpRate, wV * 4f)
                accumulateVocalsGated(stems.vocals, startF, count, vV, vocalsWarp, out)
            } else {
                accumulateVocalsGated(stems.vocals, startF, count, vV, null, out)
            }
        } else {
            if (wV > 0.001f) {
                vocalsWarp.configure(warpRate, wV * 4f)
                accumulateWarped(vocalsFx, stems.vocals, startF, count, vV, vocalsWarp, out)
            } else {
                vocalsFx.accumulate(stems.vocals, startF, count, vV, out)
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
                out[idx] = out[idx] * (1f - echoWetCur * 0.5f) + echoWetCur * echo.processLeft(mL)
                out[idx + 1] = out[idx + 1] * (1f - echoWetCur * 0.5f) + echoWetCur * echo.processRight(mR)
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

        val headroom = 0.7f
        for (f in 0 until count) {
            val idx = f * 2
            out[idx] = tanh(out[idx] * 0.7f) / 0.7f * headroom
            out[idx + 1] = tanh(out[idx + 1] * 0.7f) / 0.7f * headroom
        }

        val totalFrames = stems.frameCount
        var fadePos = startF
        for (f in 0 until count) {
            val pos = fadePos
            val sinceStart = pos - playbackStartFrame
            val remaining = totalFrames - pos
            if (sinceStart >= fadeInFrames && remaining > fadeOutFrames) {
                fadePos += tempoScale
                continue
            }
            var env = 1f
            if (sinceStart < fadeInFrames) env = (sinceStart + 1f) / fadeInFrames.toFloat()
            if (remaining <= fadeOutFrames) env = min(env, remaining / fadeOutFrames.toFloat())
            val idx = f * 2
            out[idx] *= env
            out[idx + 1] *= env
            fadePos += tempoScale
        }
    }
}

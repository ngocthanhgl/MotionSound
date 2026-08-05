package com.motionsound.stem

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.motionsound.sounddrive.BiquadFilter
import com.motionsound.sounddrive.StemFxChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

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

    private val drumsFx = StemFxChain()
    private val bassFx = StemFxChain()
    private val otherFx = StemFxChain()
    private val vocalsFx = StemFxChain()
    private val masterLpf = BiquadFilter()
    private val masterHpf = BiquadFilter()

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private val playbackHeadFrame = AtomicInteger(0)
    @Volatile private var released = false

    private val bufferFrames = 4096

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
        audioTrack?.play()

        playJob = scope.launch(Dispatchers.IO) {
            val mixBuf = FloatArray(bufferFrames * StemConfig.NUM_CHANNELS)
            val totalFrames = stems.frameCount

            var frame = startFrame
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
            }
            isPlaying.set(false)
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
    }

    fun seekToFrame(frame: Int, stems: StemResult, scope: CoroutineScope) {
        AppLogger.event("StemMixer", "SEEK", "frame=$frame")
        stop()
        play(stems, scope, startFrame = frame)
    }

    fun getPlaybackPositionSeconds(): Float =
        playbackHeadFrame.get().toFloat() / StemConfig.SAMPLE_RATE

    fun release() {
        AppLogger.event("StemMixer", "RELEASE")
        released = true
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun mix(stems: StemResult, startFrame: Int, count: Int, out: FloatArray) {
        val vD = (volumeDrums * masterVolume).coerceIn(0f, 1f)
        val vB = (volumeBass * masterVolume).coerceIn(0f, 1f)
        val vO = (volumeOther * masterVolume).coerceIn(0f, 1f)
        val vV = (volumeVocals * masterVolume).coerceIn(0f, 1f)

        out.fill(0f, 0, count * 2)

        drumsFx.pan = drumsPan
        drumsFx.filter.lowPass(drumsCutoff, drumsResonance)
        drumsFx.accumulate(stems.drums, startFrame, count, vD, out)

        bassFx.pan = bassPan
        bassFx.filter.lowPass(bassCutoff, bassResonance)
        bassFx.accumulate(stems.bass, startFrame, count, vB, out)

        otherFx.pan = otherPan
        otherFx.filter.lowPass(otherCutoff, otherResonance)
        otherFx.accumulate(stems.other, startFrame, count, vO, out)

        vocalsFx.pan = vocalsPan
        vocalsFx.filter.lowPass(vocalsCutoff, vocalsResonance)
        vocalsFx.accumulate(stems.vocals, startFrame, count, vV, out)

        masterLpf.lowPass(masterCutoff, 0.707f)
        masterHpf.highPass(masterLowCut.coerceAtLeast(0f), 0.707f)

        for (f in 0 until count) {
            val idx = f * 2
            out[idx] = masterHpf.processLeft(masterLpf.processLeft(out[idx]))
            out[idx + 1] = masterHpf.processRight(masterLpf.processRight(out[idx + 1]))
        }
    }
}

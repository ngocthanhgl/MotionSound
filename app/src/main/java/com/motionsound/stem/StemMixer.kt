package com.motionsound.stem

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private val playbackHeadFrame = AtomicInteger(0)

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
    }

    fun play(stems: StemResult, scope: CoroutineScope, startFrame: Int = 0) {
        stop()
        isPlaying.set(true)
        playbackHeadFrame.set(startFrame)
        audioTrack?.play()

        playJob = scope.launch(Dispatchers.IO) {
            val mixBuf = FloatArray(bufferFrames * StemConfig.NUM_CHANNELS)
            val totalFrames = stems.drums.size / StemConfig.NUM_CHANNELS

            var frame = startFrame
            while (isActive && isPlaying.get() && frame < totalFrames) {
                val framesToWrite = min(bufferFrames, totalFrames - frame)
                mix(stems, frame, framesToWrite, mixBuf)
                audioTrack?.write(mixBuf, 0, framesToWrite * StemConfig.NUM_CHANNELS, AudioTrack.WRITE_BLOCKING)
                frame += framesToWrite
                playbackHeadFrame.set(frame)
            }
            isPlaying.set(false)
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
        audioTrack?.stop()
        audioTrack?.flush()
        playbackHeadFrame.set(0)
    }

    fun seekToFrame(frame: Int, stems: StemResult, scope: CoroutineScope) {
        stop()
        play(stems, scope, startFrame = frame)
    }

    fun getPlaybackPositionSeconds(): Float =
        playbackHeadFrame.get().toFloat() / StemConfig.SAMPLE_RATE

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun mix(stems: StemResult, startFrame: Int, count: Int, out: FloatArray) {
        val vD = volumeDrums * masterVolume
        val vB = volumeBass * masterVolume
        val vO = volumeOther * masterVolume
        val vV = volumeVocals * masterVolume

        for (f in 0 until count) {
            for (ch in 0 until StemConfig.NUM_CHANNELS) {
                val pos = (startFrame + f) * StemConfig.NUM_CHANNELS + ch
                out[f * StemConfig.NUM_CHANNELS + ch] =
                    stems.drums[pos] * vD +
                    stems.bass[pos] * vB +
                    stems.other[pos] * vO +
                    stems.vocals[pos] * vV
            }
        }
    }
}

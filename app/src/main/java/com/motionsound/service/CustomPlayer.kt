package com.motionsound.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.motionsound.drive.DspProcessor
import com.motionsound.drive.EqStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PlayerControlState(
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0
)

private enum class PlayerState { IDLE, PREPARING, PLAYING, PAUSED, STOPPED }

class CustomPlayer(private val context: Context) {

    private val _state = MutableStateFlow(PlayerControlState())
    val state: StateFlow<PlayerControlState> = _state.asStateFlow()

    private var playlist = listOf<String>()
    private var currentIndex = -1
    private var playerState = PlayerState.IDLE

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var dsp: DspProcessor? = null

    private var sampleRate = 0
    private var channels = 0
    private var totalSamplesWritten = 0L
    private var isFloatOutput = true

    private var pipelineJob: Job? = null
    private var scope: CoroutineScope? = null

    private val lock = Any()

    fun setPlaylist(uris: List<String>, startIndex: Int) {
        synchronized(lock) {
            playlist = uris
            playAt(startIndex)
        }
    }

    private fun stopPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        scope = null
        synchronized(lock) {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            decoder?.stop()
            decoder?.release()
            decoder = null
            extractor?.release()
            extractor = null
        }
        playerState = PlayerState.IDLE
    }

    private fun playAt(index: Int) {
        if (index !in playlist.indices) return
        stopPipeline()
        playerState = PlayerState.PREPARING
        currentIndex = index
        _state.value = _state.value.copy(currentIndex = index, durationMs = 0L, sampleRate = 0)

        scope = CoroutineScope(Dispatchers.Default)
        pipelineJob = scope?.launch {
            try {
                openAndPlay(playlist[index])
            } catch (_: Exception) {
                playerState = PlayerState.IDLE
            }
        }
    }

    private suspend fun openAndPlay(uri: String) {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(context, Uri.parse(uri), null)
        } catch (e: Exception) {
            ext.release()
            nextOrStop()
            return
        }

        val trackIdx = selectAudioTrack(ext) ?: run { ext.release(); nextOrStop(); return }
        ext.selectTrack(trackIdx)

        val format = ext.getTrackFormat(trackIdx)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { ext.release(); nextOrStop(); return }
        val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val dur = format.getLong(MediaFormat.KEY_DURATION)

        val oldSr = sampleRate
        sampleRate = sr; channels = ch

        dsp = DspProcessor(sr.toFloat())

        val dec = MediaCodec.createDecoderByType(mime)
        val audioFormat = MediaFormat.createAudioFormat(mime, sr, ch)
        var useFloat = false
        audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        try {
            dec.configure(audioFormat, null, null, 0)
            useFloat = true
        } catch (_: Exception) {
            audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            dec.configure(audioFormat, null, null, 0)
        }
        isFloatOutput = useFloat
        dec.start()

        val track = buildAudioTrack(sr, ch)
        track.play()

        synchronized(lock) {
            decoder = dec
            extractor = ext
            audioTrack = track
            playerState = PlayerState.PLAYING
            totalSamplesWritten = 0L
            _state.value = _state.value.copy(isPlaying = true, sampleRate = sr, durationMs = dur / 1000)
        }

        val bufInfo = BufferInfo()
        var outputDone = false
        var sawEOS = false
        var codecConfig = true

        while (coroutineContext.isActive && playerState != PlayerState.STOPPED && !outputDone) {
            if (playerState == PlayerState.PAUSED) {
                delay(50)
                continue
            }

            if (!sawEOS) {
                val inIdx = dec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx) ?: continue
                    val sampleSize = ext.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        buf.position(0)
                        buf.limit(sampleSize)
                        dec.queueInputBuffer(inIdx, 0, sampleSize, ext.sampleTime, 0)
                        ext.advance()
                    }
                }
            }

            var outIdx = dec.dequeueOutputBuffer(bufInfo, 5000)
            while (outIdx >= 0) {
                if (codecConfig && (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    dec.releaseOutputBuffer(outIdx, false)
                    outIdx = dec.dequeueOutputBuffer(bufInfo, 0)
                    continue
                }
                codecConfig = false

                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                    dec.releaseOutputBuffer(outIdx, false)
                    break
                }

                val outBuf = dec.getOutputBuffer(outIdx) ?: continue
                val pcm = decodeToFloats(outBuf, bufInfo)
                dec.releaseOutputBuffer(outIdx, false)

                dsp?.process(
                    pcm,
                    EqStateStore.bandGains,
                    EqStateStore.reverbMix,
                    EqStateStore.volumeReductionDb
                )

                audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                totalSamplesWritten += pcm.size / channels

                outIdx = dec.dequeueOutputBuffer(bufInfo, 0)
            }

            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = dec.outputFormat
                val newSr = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val newCh = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (newSr != sampleRate || newCh != channels) {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = buildAudioTrack(newSr, newCh)
                    audioTrack?.play()
                    sampleRate = newSr; channels = newCh
                    dsp = DspProcessor(newSr.toFloat())
                }
            }
        }

        track.stop()
        track.release()
        dec.stop()
        dec.release()
        ext.release()

        synchronized(lock) {
            audioTrack = null
            decoder = null
            extractor = null
        }

        if (outputDone && playerState != PlayerState.STOPPED) {
            nextOrStop()
        }
    }

    private fun decodeToFloats(buf: ByteBuffer, info: BufferInfo): FloatArray {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        if (isFloatOutput) {
            val remaining = buf.remaining()
            val floats = FloatArray(remaining / 4)
            buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            return floats
        }
        val shorts = ShortArray(buf.remaining() / 2)
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return FloatArray(shorts.size) { i -> shorts[i] / 32768f }
    }

    private fun selectAudioTrack(ext: MediaExtractor): Int? {
        for (i in 0 until ext.trackCount) {
            val fmt = ext.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return null
    }

    private fun buildAudioTrack(sr: Int, ch: Int): AudioTrack {
        val channelMask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sr, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sr)
                .setChannelMask(channelMask)
                .build())
            .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(minBuf))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun nextOrStop() {
        synchronized(lock) {
            if (currentIndex + 1 in playlist.indices) {
                playAt(currentIndex + 1)
            } else {
                playerState = PlayerState.IDLE
                currentIndex = -1
                _state.value = PlayerControlState()
            }
        }
    }

    fun play() {
        synchronized(lock) {
            if (playerState == PlayerState.PAUSED) {
                playerState = PlayerState.PLAYING
                audioTrack?.play()
                _state.value = _state.value.copy(isPlaying = true)
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (playerState == PlayerState.PLAYING) {
                playerState = PlayerState.PAUSED
                audioTrack?.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    fun togglePlayPause() {
        if (playerState == PlayerState.PLAYING) pause()
        else if (playerState == PlayerState.PAUSED) play()
    }

    fun playNext() {
        synchronized(lock) {
            if (currentIndex + 1 in playlist.indices) playAt(currentIndex + 1)
        }
    }

    fun playPrevious() {
        synchronized(lock) {
            if (currentIndex - 1 in playlist.indices) playAt(currentIndex - 1)
        }
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            val ex = extractor ?: return
            val us = positionMs * 1000
            ex.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            decoder?.flush()
            totalSamplesWritten = (positionMs * sampleRate * channels) / 1000L
        }
    }

    fun getCurrentPosition(): Long {
        if (sampleRate <= 0 || channels <= 0) return 0L
        return totalSamplesWritten * 1000L / (sampleRate * channels)
    }

    fun hasNext(): Boolean = currentIndex + 1 in playlist.indices
    fun hasPrevious(): Boolean = currentIndex - 1 in playlist.indices

    fun release() {
        synchronized(lock) {
            playerState = PlayerState.STOPPED
            pipelineJob?.cancel()
            pipelineJob = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            decoder?.stop()
            decoder?.release()
            decoder = null
            extractor?.release()
            extractor = null
        }
    }
}

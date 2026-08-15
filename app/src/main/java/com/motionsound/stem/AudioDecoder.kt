package com.motionsound.stem

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.roundToInt

class AudioDecoder(private val context: Context) {

    suspend fun decode(uri: Uri): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor) ?: return@withContext null.also {
            }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null.also {
            }
            val srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION) / 1000

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmSamples = drainCodec(extractor, codec, srcCh)
            codec.stop()
            codec.release()

            val stereo = if (srcCh == 1) {
                monoToStereo(pcmSamples)
            } else pcmSamples

            val result = if (srcSr == StemConfig.SAMPLE_RATE) {
                stereo
            } else {
                resample(stereo, srcSr, StemConfig.SAMPLE_RATE)
            }

            result

        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun drainCodec(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channelCount: Int
    ): FloatArray {
        val bufferInfo = MediaCodec.BufferInfo()
        val chunks = mutableListOf<FloatArray>()
        var totalSamples = 0
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val bytesRead = extractor.readSampleData(buf, 0)
                    if (bytesRead < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, bytesRead, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuf = buf.asShortBuffer()
                val count = shortBuf.remaining()
                if (count > 0) {
                    val shorts = ShortArray(count)
                    shortBuf.get(shorts)
                    val floats = FloatArray(count) { shorts[it].toFloat() / 32768f }
                    chunks.add(floats)
                    totalSamples += count
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]

        val result = FloatArray(totalSamples)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun monoToStereo(mono: FloatArray): FloatArray {
        val stereo = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        return stereo
    }

    private fun resample(input: FloatArray, srcSr: Int, dstSr: Int): FloatArray {
        if (srcSr == dstSr) return input
        val ratio = dstSr.toDouble() / srcSr.toDouble()
        val srcFrames = input.size / 2
        val dstFrames = (srcFrames * ratio).roundToInt()
        val output = FloatArray(dstFrames * 2)

        for (dstFrame in 0 until dstFrames) {
            val srcPos = dstFrame / ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val nextIdx = (srcIdx + 1).coerceAtMost(srcFrames - 1)

            for (ch in 0..1) {
                val a = input[srcIdx * 2 + ch]
                val b = input[nextIdx * 2 + ch]
                output[dstFrame * 2 + ch] = a + frac * (b - a)
            }
        }
        return output
    }
}

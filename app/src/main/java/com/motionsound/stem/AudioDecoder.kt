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
                android.util.Log.w("AudioDecoder", "No audio track: $uri")
            }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null.also {
                android.util.Log.w("AudioDecoder", "No MIME type: $uri")
            }
            val srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            val pcmSamples: FloatArray
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                pcmSamples = drainCodec(extractor, codec, srcCh)
                codec.stop()
            } finally {
                try { codec.release() } catch (_: Exception) {}
            }

            val stereo = when {
                srcCh == 1 -> monoToStereo(pcmSamples)
                srcCh == 2 -> pcmSamples
                else -> foldToStereo(pcmSamples, srcCh)
            }

            if (srcSr == StemConfig.SAMPLE_RATE) stereo
            else resample(stereo, srcSr, StemConfig.SAMPLE_RATE)

        } catch (e: Exception) {
            android.util.Log.w("AudioDecoder", "Decode failed: $uri (${e::class.simpleName}: ${e.message})")
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
        var tryAgainStreak = 0

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
                tryAgainStreak = 0
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
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (++tryAgainStreak > 500) {
                    throw IllegalStateException("Decoder stalled: no output for ~5s")
                }
            } else {
                tryAgainStreak = 0
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

    private fun foldToStereo(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            var acc = 0f
            val base = f * channels
            for (c in 0 until channels) acc += interleaved[base + c]
            val v = acc / channels
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        return out
    }

    private fun resample(input: FloatArray, srcSr: Int, dstSr: Int): FloatArray {
        if (srcSr == dstSr) return input
        var src = input
        if (dstSr < srcSr) {
            val k = (srcSr / dstSr).coerceAtLeast(1)
            if (k > 1) src = boxFilter(src, k)
        }
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
                val a = src[srcIdx * 2 + ch]
                val b = src[nextIdx * 2 + ch]
                output[dstFrame * 2 + ch] = a + frac * (b - a)
            }
        }
        return output
    }

    private fun boxFilter(stereo: FloatArray, k: Int): FloatArray {
        val frames = stereo.size / 2
        if (frames == 0 || k <= 1) return stereo
        val out = FloatArray(frames * 2)
        var accL = 0f
        var accR = 0f
        for (i in 0 until frames) {
            accL += stereo[i * 2]
            accR += stereo[i * 2 + 1]
            if (i >= k) {
                accL -= stereo[(i - k) * 2]
                accR -= stereo[(i - k) * 2 + 1]
            }
            val n = minOf(i + 1, k)
            out[i * 2] = accL / n
            out[i * 2 + 1] = accR / n
        }
        return out
    }
}

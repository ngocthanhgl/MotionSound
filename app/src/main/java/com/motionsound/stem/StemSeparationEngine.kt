package com.motionsound.stem

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

data class StemResult(
    val drums: FloatArray,
    val bass: FloatArray,
    val other: FloatArray,
    val vocals: FloatArray,
    val sampleRate: Int
) {
    fun stemAt(index: Int) = when (index) {
        StemConfig.STEM_DRUMS -> drums
        StemConfig.STEM_BASS -> bass
        StemConfig.STEM_OTHER -> other
        StemConfig.STEM_VOCALS -> vocals
        else -> throw IllegalArgumentException("Unknown stem index $index")
    }
}

class StemSeparationEngine(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun initialize(): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                addNnapi()
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val modelBytes = context.assets.open(StemConfig.MODEL_ASSET_PATH).readBytes()
            session = env!!.createSession(modelBytes, opts)
            true
        } catch (e: Throwable) {
            env?.close()
            env = null
            session = null
            false
        }
    }

    fun release() {
        session?.close()
        env?.close()
        session = null
        env = null
    }

    fun isLoaded(): Boolean = session != null

    suspend fun separate(
        stereoInterleaved: FloatArray,
        onProgress: (Float) -> Unit = {}
    ): StemResult? = withContext(Dispatchers.Default) {
        val currentSession = session ?: return@withContext null
        val currentEnv = env ?: return@withContext null

        val totalFrames = stereoInterleaved.size / 2
        val numChunks = computeNumChunks(totalFrames)

        val outputStems = Array(StemConfig.NUM_STEMS) { FloatArray(stereoInterleaved.size) }
        val windowSum = FloatArray(stereoInterleaved.size)

        val window = hannWindow(StemConfig.CHUNK_SAMPLES)

        for (chunkIdx in 0 until numChunks) {
            val frameStart = chunkIdx * StemConfig.HOP_SAMPLES
            val frameEnd = (frameStart + StemConfig.CHUNK_SAMPLES).coerceAtMost(totalFrames)
            val chunkFrames = frameEnd - frameStart

            val chunkInput = FloatArray(StemConfig.NUM_CHANNELS * StemConfig.CHUNK_SAMPLES)
            for (ch in 0 until StemConfig.NUM_CHANNELS) {
                for (f in 0 until chunkFrames) {
                    chunkInput[ch * StemConfig.CHUNK_SAMPLES + f] =
                        stereoInterleaved[(frameStart + f) * StemConfig.NUM_CHANNELS + ch]
                }
            }

            val buf = FloatBuffer.wrap(chunkInput)
            val inputTensor = OnnxTensor.createTensor(
                currentEnv, buf,
                longArrayOf(1L, StemConfig.NUM_CHANNELS.toLong(), StemConfig.CHUNK_SAMPLES.toLong())
            )

            val resultMap = currentSession.run(mapOf("mix" to inputTensor))
            inputTensor.close()

            val outputTensor = resultMap["stems"]!! as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val raw = outputTensor.value as Array<Array<Array<FloatArray>>>

            resultMap.close()

            for (stemIdx in 0 until StemConfig.NUM_STEMS) {
                for (f in 0 until chunkFrames) {
                    val w = window[f]
                    for (ch in 0 until StemConfig.NUM_CHANNELS) {
                        val outPos = (frameStart + f) * StemConfig.NUM_CHANNELS + ch
                        outputStems[stemIdx][outPos] += raw[0][stemIdx][ch][f] * w
                    }
                    if (stemIdx == 0) {
                        val sumPos = (frameStart + f) * StemConfig.NUM_CHANNELS
                        windowSum[sumPos] += w
                        windowSum[sumPos + 1] += w
                    }
                }
            }

            onProgress((chunkIdx + 1).toFloat() / numChunks)
        }

        for (stemIdx in 0 until StemConfig.NUM_STEMS) {
            for (i in outputStems[stemIdx].indices) {
                val w = windowSum[i]
                if (w > 1e-6f) outputStems[stemIdx][i] /= w
            }
        }

        StemResult(
            drums = outputStems[StemConfig.STEM_DRUMS],
            bass = outputStems[StemConfig.STEM_BASS],
            other = outputStems[StemConfig.STEM_OTHER],
            vocals = outputStems[StemConfig.STEM_VOCALS],
            sampleRate = StemConfig.SAMPLE_RATE
        )
    }

    private fun computeNumChunks(totalFrames: Int): Int {
        if (totalFrames <= StemConfig.CHUNK_SAMPLES) return 1
        return ceil(
            (totalFrames - StemConfig.OVERLAP_SAMPLES).toDouble() / StemConfig.HOP_SAMPLES
        ).toInt()
    }

    private fun hannWindow(size: Int): FloatArray =
        FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
}

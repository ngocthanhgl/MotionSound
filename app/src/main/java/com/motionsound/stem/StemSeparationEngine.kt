package com.motionsound.stem

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
    @Volatile var lastError: String? = null

    fun initialize(onDownloadProgress: (Float) -> Unit = {}): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
            }
            val modelFile = File(context.cacheDir, "htdemucs_fp16weights.onnx")
            val EXPECTED_SIZE = 150_000_000L

            if (modelFile.exists() && modelFile.length() >= EXPECTED_SIZE) {
                Log.i(TAG, "Using cached model (${modelFile.length()} bytes)")
            } else {
                modelFile.delete()
                var gotModel = false
                try {
                    Log.i(TAG, "Copying model from assets")
                    context.assets.open(StemConfig.MODEL_ASSET_PATH).use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    gotModel = true
                } catch (e: Exception) {
                    Log.w(TAG, "Asset copy failed", e)
                }
                if (!gotModel) {
                    Log.i(TAG, "Downloading model from HuggingFace")
                    downloadModel(modelFile, onDownloadProgress)
                }
                Log.i(TAG, "Model file ready (${modelFile.length()} bytes)")
            }

            session = env!!.createSession(modelFile.absolutePath, opts)
            Log.i(TAG, "Model loaded successfully")
            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            Log.e(TAG, "Model init failed: $lastError", e)
            session = null
            false
        }
    }

    private fun downloadModel(file: File, onProgress: (Float) -> Unit) {
        val url = URL("https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()
        val totalBytes = conn.contentLengthLong
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(file).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                var total = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    total += read
                    if (totalBytes > 0) onProgress(total.toFloat() / totalBytes)
                }
            }
        }
    }

    companion object {
        private const val TAG = "StemSeparationEngine"
    }

    fun release() {
        session?.close()
        session = null
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

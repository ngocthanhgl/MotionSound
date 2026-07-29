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
    @Volatile var debugLogger: DebugLogger? = null

    fun initialize(onDownloadProgress: (Float) -> Unit = {}): Boolean {
        val log = DebugLogger(context)
        debugLogger = log
        log.log("Init", "========================================")
        log.log("Init", "StemSeparationEngine.initialize() begin")

        return try {
            log.log("Init", "Step 1: OrtEnvironment.getEnvironment()")
            env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Throwable) {
                log.log("Init", e)
                throw e
            }
            log.log("Init", "OrtEnvironment OK")

            log.log("Init", "Step 2: OrtSession.SessionOptions()")
            val opts = OrtSession.SessionOptions().apply {
                log.log("Init", "Setting intra op threads = 4")
                setIntraOpNumThreads(4)
                log.log("Init", "Setting inter op threads = 2")
                setInterOpNumThreads(2)
            }

            val modelFile = File(context.cacheDir, "htdemucs_fp16weights.onnx")
            log.log("Init", "Model file path: ${modelFile.absolutePath}")

            log.log("Init", "Step 3: Check cache dir writable")
            val cacheDir = context.cacheDir
            log.log("Init", "cacheDir: ${cacheDir.absolutePath}")
            log.log("Init", "cacheDir exists: ${cacheDir.exists()}")
            log.log("Init", "cacheDir canWrite: ${cacheDir.canWrite()}")
            log.log("Init", "cacheDir free space: ${cacheDir.freeSpace / (1024*1024)} MB")

            log.log("Init", "Step 4: Check existing model file")
            if (modelFile.exists()) {
                log.log("Init", "Model file EXISTS, size=${modelFile.length()}, expected=150000000")
                log.log("Init", "Model file readable: ${modelFile.canRead()}")
            } else {
                log.log("Init", "Model file does NOT exist")
            }

            val EXPECTED_SIZE = 150_000_000L
            if (modelFile.exists() && modelFile.length() >= EXPECTED_SIZE) {
                log.log("Init", "Using cached model (${modelFile.length()} bytes)")
            } else {
                if (modelFile.exists()) {
                    log.log("Init", "Cached model too small (${modelFile.length()} bytes), deleting")
                    modelFile.delete()
                }

                log.log("Init", "Step 5: Check assets for bundled model")
                var bundledSize = -1L
                var gotModel = false
                try {
                    val assetFileDescriptor = context.assets.openFd(StemConfig.MODEL_ASSET_PATH)
                    bundledSize = assetFileDescriptor.length
                    assetFileDescriptor.close()
                    log.log("Init", "Bundled model FOUND, size=$bundledSize bytes")
                } catch (e: Exception) {
                    log.log("Init", "Bundled model NOT found in assets: ${e.message}")
                }

                if (bundledSize > 0) {
                    log.log("Init", "Step 6: Copying model from assets to cache")
                    log.log("Init", "Source: ${StemConfig.MODEL_ASSET_PATH}")
                    try {
                        context.assets.open(StemConfig.MODEL_ASSET_PATH).use { input ->
                            FileOutputStream(modelFile).use { output ->
                                val buf = ByteArray(65536)
                                var totalCopied = 0L
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                    totalCopied += read
                                }
                                log.log("Init", "Copy complete: $totalCopied bytes written")
                            }
                        }
                        gotModel = true
                        log.log("Init", "Copied model size: ${modelFile.length()} bytes")
                    } catch (e: Exception) {
                        log.log("Init", "Asset copy FAILED", e)
                        modelFile.delete()
                    }
                } else {
                    log.log("Init", "No bundled model in assets")
                }

                if (!gotModel) {
                    log.log("Init", "Step 7: Downloading model from HuggingFace")
                    log.log("Init", "URL: https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx")
                    try {
                        downloadModel(modelFile, onDownloadProgress, log)
                        log.log("Init", "Download complete: ${modelFile.length()} bytes")
                    } catch (e: Exception) {
                        log.log("Init", "Download FAILED", e)
                        throw e
                    }
                }

                log.log("Init", "Final model file check:")
                log.log("Init", "  exists=${modelFile.exists()}, size=${modelFile.length()}, readable=${modelFile.canRead()}")
            }

            log.log("Init", "Step 8: env!!.createSession()")
            log.log("Init", "Model path: ${modelFile.absolutePath}")
            session = try {
                env!!.createSession(modelFile.absolutePath, opts)
            } catch (e: Throwable) {
                log.log("Init", "createSession FAILED", e)
                throw e
            }
            log.log("Init", "Session created: ${session?.sessionId ?: "null"}")
            log.log("Init", "Model input count: ${session?.inputInfo?.size}")
            log.log("Init", "Model output count: ${session?.outputInfo?.size}")

            log.log("Init", "========================================")
            Log.i(TAG, "Model loaded successfully")
            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            log.log("Init", "FATAL: $lastError")
            log.log("Init", "========================================")
            Log.e(TAG, "Model init failed: $lastError", e)
            session = null
            false
        }
    }

    private fun downloadModel(file: File, onProgress: (Float) -> Unit, log: DebugLogger) {
        log.log("Download", "Opening HTTP connection")
        val url = URL("https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        log.log("Download", "Connecting...")
        conn.connect()
        val responseCode = conn.responseCode
        log.log("Download", "Response code: $responseCode")
        if (responseCode != 200) {
            throw RuntimeException("HTTP $responseCode from HuggingFace")
        }
        val totalBytes = conn.contentLengthLong
        log.log("Download", "Total bytes: $totalBytes")

        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(file).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                var total = 0L
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    total += read
                    if (totalBytes > 0) {
                        val pct = (total * 100 / totalBytes).toInt()
                        if (pct >= lastPct + 10) {
                            log.log("Download", "Progress: $pct% ($total/$totalBytes)")
                            lastPct = pct
                        }
                        onProgress(total.toFloat() / totalBytes)
                    }
                }
                log.log("Download", "Download completed: $total bytes received")
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

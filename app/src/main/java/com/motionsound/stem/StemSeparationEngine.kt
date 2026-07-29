package com.motionsound.stem

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

data class StemResult(
    val drums: FloatBuffer,
    val bass: FloatBuffer,
    val other: FloatBuffer,
    val vocals: FloatBuffer,
    val sampleRate: Int,
    val frameCount: Int
) {
    fun stemAt(index: Int): FloatBuffer = when (index) {
        StemConfig.STEM_DRUMS -> drums
        StemConfig.STEM_BASS -> bass
        StemConfig.STEM_OTHER -> other
        StemConfig.STEM_VOCALS -> vocals
        else -> throw IllegalArgumentException("Unknown stem index $index")
    }
}

class StemSeparationEngine(private val context: Context) {

    @Volatile private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    @Volatile var lastError: String? = null
    private val inferenceMutex = Mutex()

    fun initialize(onDownloadProgress: (Float) -> Unit = {}): Boolean {
        AppLogger.event("Engine", "INIT_START")

        return try {
            AppLogger.event("Engine", "GET_ORT_ENV")
            env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Throwable) {
                AppLogger.error("Engine", "OrtEnvironment failed", e)
                throw e
            }
            AppLogger.event("Engine", "ORT_ENV_OK")

            AppLogger.event("Engine", "CREATE_SESSION_OPTIONS")
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
            }

            val modelFile = File(context.cacheDir, "htdemucs_fp16weights.onnx")

            if (modelFile.exists() && modelFile.length() >= 150_000_000L) {
                AppLogger.i("Engine", "Using cached model ${modelFile.length()} bytes")
            } else {
                if (modelFile.exists()) {
                    AppLogger.w("Engine", "Cached model too small ${modelFile.length()}, deleting")
                    modelFile.delete()
                }

                var gotModel = false
                try {
                    val afd = context.assets.openFd(StemConfig.MODEL_ASSET_PATH)
                    val size = afd.length
                    afd.close()
                    AppLogger.i("Engine", "Bundled model found, size=$size bytes")
                    context.assets.open(StemConfig.MODEL_ASSET_PATH).use { input ->
                        FileOutputStream(modelFile).use { output ->
                            val buf = ByteArray(65536)
                            var total = 0L
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                total += read
                            }
                            AppLogger.i("Engine", "Copied $total bytes from assets")
                        }
                    }
                    gotModel = true
                } catch (e: Exception) {
                    AppLogger.w("Engine", "No bundled model in assets: ${e.message}")
                }

                if (!gotModel) {
                    AppLogger.event("Engine", "DOWNLOAD_START")
                    downloadModel(modelFile, onDownloadProgress)
                }

                if (!modelFile.exists() || modelFile.length() < 150_000_000L) {
                    throw RuntimeException("Model file missing/incomplete: ${modelFile.length()} bytes")
                }
            }

            AppLogger.event("Engine", "CREATE_SESSION", modelFile.absolutePath)
            session = try {
                env!!.createSession(modelFile.absolutePath, opts)
            } catch (e: Throwable) {
                AppLogger.error("Engine", "createSession failed", e)
                throw e
            }
            AppLogger.i("Engine", "Session: ${session}")
            AppLogger.i("Engine", "Inputs: ${session?.inputInfo}")
            AppLogger.i("Engine", "Outputs: ${session?.outputInfo}")

            AppLogger.event("Engine", "INIT_DONE")
            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            AppLogger.error("Engine", "INIT_FAILED: $lastError", e)
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
        val responseCode = conn.responseCode
        AppLogger.i("Engine", "Download HTTP $responseCode")
        if (responseCode != 200) throw RuntimeException("HTTP $responseCode")

        val totalBytes = conn.contentLengthLong
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
                        if (pct >= lastPct + 10) { lastPct = pct; AppLogger.i("Engine", "Download $pct%") }
                        onProgress(total.toFloat() / totalBytes)
                    }
                }
                AppLogger.i("Engine", "Downloaded $total bytes")
            }
        }
    }

    fun release() {
        AppLogger.event("Engine", "RELEASE")
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

        val totalSize = stereoInterleaved.size
        val totalFrames = totalSize / 2
        val numChunks = computeNumChunks(totalFrames)
        AppLogger.event("Engine", "SEPARATE", "frames=$totalFrames chunks=$numChunks")

        val stamp = System.nanoTime()
        val tempDir = context.cacheDir
        val stemFiles = Array(StemConfig.NUM_STEMS) { i ->
            File(tempDir, "sep_${stamp}_$i.tmp")
        }
        val winFile = File(tempDir, "sep_${stamp}_win.tmp")
        var result: StemResult? = null
        try {
            stemFiles.forEach { RandomAccessFile(it, "rw").use { raf -> raf.setLength(totalSize * 4L) } }
            RandomAccessFile(winFile, "rw").use { raf -> raf.setLength(totalSize * 4L) }

            val window = hannWindow(StemConfig.CHUNK_SAMPLES)
            val chunkInput = FloatArray(StemConfig.NUM_CHANNELS * StemConfig.CHUNK_SAMPLES)

            for (chunkIdx in 0 until numChunks) {
                val frameStart = chunkIdx * StemConfig.HOP_SAMPLES
                val frameEnd = (frameStart + StemConfig.CHUNK_SAMPLES).coerceAtMost(totalFrames)
                val chunkFrames = frameEnd - frameStart

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
                val resultMap: OrtSession.Result = try {
                    inferenceMutex.withLock {
                        currentSession.run(mapOf("mix" to inputTensor))
                    }
                } catch (e: Exception) {
                    inputTensor.close(); throw e
                }
                inputTensor.close()

                val outputTensor = resultMap["stems"] as? OnnxTensor
                if (outputTensor == null) {
                    AppLogger.w("Engine", "STEM_TENSOR_NULL ${resultMap.keys}")
                    resultMap.close(); return@withContext null
                }
                val fb = outputTensor.byteBuffer
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                val chunkOut = FloatArray(fb.remaining())
                fb.get(chunkOut)
                resultMap.close()

                val chunkSamples = chunkFrames * StemConfig.NUM_CHANNELS
                val bytePos = frameStart * StemConfig.NUM_CHANNELS * 4L
                val S2 = StemConfig.CHUNK_SAMPLES
                val readBuf = ByteArray(chunkSamples * 4)
                val floatBuf = ByteBuffer.wrap(readBuf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                for (stemIdx in 0 until StemConfig.NUM_STEMS) {
                    val stemBase = stemIdx * StemConfig.NUM_CHANNELS * S2

                    RandomAccessFile(stemFiles[stemIdx], "r").use { raf ->
                        raf.seek(bytePos); raf.read(readBuf)
                    }
                    for (f in 0 until chunkFrames) {
                        val w = window[f]
                        for (ch in 0 until StemConfig.NUM_CHANNELS) {
                            val idx = f * StemConfig.NUM_CHANNELS + ch
                            floatBuf.put(idx, floatBuf.get(idx) + chunkOut[stemBase + ch * S2 + f] * w)
                        }
                    }
                    RandomAccessFile(stemFiles[stemIdx], "rw").use { raf ->
                        raf.seek(bytePos); raf.write(readBuf)
                    }

                    if (stemIdx == 0) {
                        RandomAccessFile(winFile, "r").use { raf ->
                            raf.seek(bytePos); raf.read(readBuf)
                        }
                        for (f in 0 until chunkFrames) {
                            val w = window[f]
                            for (ch in 0 until StemConfig.NUM_CHANNELS) {
                                val idx = f * StemConfig.NUM_CHANNELS + ch
                                floatBuf.put(idx, floatBuf.get(idx) + w)
                            }
                        }
                        RandomAccessFile(winFile, "rw").use { raf ->
                            raf.seek(bytePos); raf.write(readBuf)
                        }
                    }
                }

                onProgress((chunkIdx + 1).toFloat() / numChunks)
            }

            val winBytes = winFile.readBytes()
            val winFb = ByteBuffer.wrap(winBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            for (stemIdx in 0 until StemConfig.NUM_STEMS) {
                val bytes = stemFiles[stemIdx].readBytes()
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until totalSize) {
                    val w = winFb.get(i)
                    if (w > 1e-6f) fb.put(i, fb.get(i) / w)
                }
                stemFiles[stemIdx].writeBytes(bytes)
            }

            AppLogger.event("Engine", "SEPARATE_DONE", "output=$totalFrames frames")

            val drums = ByteBuffer.wrap(stemFiles[0].readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val bass = ByteBuffer.wrap(stemFiles[1].readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val other = ByteBuffer.wrap(stemFiles[2].readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val vocals = ByteBuffer.wrap(stemFiles[3].readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            result = StemResult(
                drums = drums, bass = bass, other = other, vocals = vocals,
                sampleRate = StemConfig.SAMPLE_RATE, frameCount = totalFrames
            )
        } catch (e: Throwable) {
            AppLogger.error("Engine", "SEPARATE_CRASHED", e)
        } finally {
            stemFiles.forEach { it.delete() }; winFile.delete()
        }
        result
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

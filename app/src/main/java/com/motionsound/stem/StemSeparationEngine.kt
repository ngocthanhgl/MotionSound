package com.motionsound.stem

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
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
import java.nio.channels.FileChannel
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
    @Volatile private var parallelInference = false
    @Volatile var throttled = false
    private var cpuSessions: List<OrtSession> = emptyList()

    fun initialize(onDownloadProgress: (Float) -> Unit = {}): Boolean {

        return try {
            env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Throwable) {
                AppLogger.error("Engine", "OrtEnvironment failed", e)
                throw e
            }

            val modelFile = File(context.cacheDir, "htdemucs_fp16weights.onnx")


            if (modelFile.exists() && modelFile.length() >= 150_000_000L) {
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
                    downloadModel(modelFile, onDownloadProgress)
                }

                if (!modelFile.exists() || modelFile.length() < 150_000_000L) {
                    throw RuntimeException("Model file missing/incomplete: ${modelFile.length()} bytes")
                }
            }

            if (session == null) {
                session = createSessionWithGradient(modelFile.absolutePath)
            }

            AppLogger.event("Engine", "INIT_DONE")
            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            AppLogger.error("Engine", "INIT_FAILED: $lastError", e)
            session = null
            false
        }
    }

    private fun createSessionWithGradient(modelPath: String): OrtSession {
        return createCpuSession(modelPath)[0].also {
        }
    }

    private fun createCpuSession(modelPath: String): List<OrtSession> {
        val sessions = mutableListOf<OrtSession>()
        val first = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)
        }
        val second = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)
        }
        try {
            sessions.add(env!!.createSession(modelPath, first))
            sessions.add(env!!.createSession(modelPath, second))
            parallelInference = true
            cpuSessions = sessions
        } catch (e: Throwable) {
            AppLogger.w("Engine", "Parallel CPU sessions failed, falling back to single 8-thread: ${e.message}")
            sessions.forEach { runCatching { it.close() } }
            cpuSessions = emptyList()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(8)
                setInterOpNumThreads(4)
            }
            val single = env!!.createSession(modelPath, opts)
            cpuSessions = listOf(single)
            parallelInference = false
        }
        return cpuSessions
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
        cpuSessions.forEach { runCatching { it.close() } }
        cpuSessions = emptyList()
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

        inferenceMutex.withLock {
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
            val S2 = StemConfig.CHUNK_SAMPLES

            suspend fun inferChunk(chunkIdx: Int, sess: OrtSession): FloatArray? {
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
                val resultMap: OrtSession.Result = try {
                    sess.run(mapOf("mix" to inputTensor))
                } catch (e: Exception) {
                    inputTensor.close(); throw e
                }
                inputTensor.close()

                val outputTensor = resultMap["stems"].orElse(null) as? OnnxTensor
                if (outputTensor == null) {
                    val keys = resultMap.iterator().asSequence().map { it.key }.joinToString()
                    AppLogger.w("Engine", "STEM_TENSOR_NULL keys=$keys")
                    resultMap.close()
                    return null
                }
                val fb = outputTensor.byteBuffer
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                val chunkOut = FloatArray(fb.remaining())
                fb.get(chunkOut)
                resultMap.close()
                return chunkOut
            }

            fun mergeChunk(chunkIdx: Int, chunkOut: FloatArray) {
                val frameStart = chunkIdx * StemConfig.HOP_SAMPLES
                val frameEnd = (frameStart + StemConfig.CHUNK_SAMPLES).coerceAtMost(totalFrames)
                val chunkFrames = frameEnd - frameStart
                val chunkSamples = chunkFrames * StemConfig.NUM_CHANNELS
                val bytePos = frameStart * StemConfig.NUM_CHANNELS * 4L
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
            }

            if (parallelInference && cpuSessions.size > 1 && numChunks > 1) {
                val p = cpuSessions.size
                val dispatcher = Dispatchers.Default.limitedParallelism(p)
                var cancelled = false
                coroutineScope {
                    var chunkIdx = 0
                    outer@ while (chunkIdx < numChunks) {
                        if (!isActive) {
                            cancelled = true
                            break@outer
                        }
                        val jobs = ArrayList<Deferred<FloatArray?>>()
                        repeat(p) { off ->
                            val idx = chunkIdx + off
                            if (idx < numChunks) {
                                val sess = cpuSessions[off]
                                jobs.add(async(dispatcher) { inferChunk(idx, sess) })
                            }
                        }
                        for ((i, job) in jobs.withIndex()) {
                            val out = job.await()
                            if (out == null) {
                                cancelled = true
                                break@outer
                            }
                            mergeChunk(chunkIdx + i, out)
                            onProgress((chunkIdx + i + 1).toFloat() / numChunks)
                        }
                        chunkIdx += jobs.size
                    }
                }
                if (cancelled) {
                    AppLogger.w("Engine", "SEPARATE_CANCELLED")
                    return@withContext null
                }
            } else {
                for (chunkIdx in 0 until numChunks) {
                    if (!isActive) {
                        AppLogger.w("Engine", "SEPARATE_CANCELLED")
                        return@withContext null
                    }
                    val out = inferChunk(chunkIdx, currentSession) ?: return@withContext null
                    mergeChunk(chunkIdx, out)
                    onProgress((chunkIdx + 1).toFloat() / numChunks)
                }
            }

            run {
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
            }


            val drums = mmapFloat(stemFiles[0])
            val bass = mmapFloat(stemFiles[1])
            val other = mmapFloat(stemFiles[2])
            val vocals = mmapFloat(stemFiles[3])

            result = StemResult(
                drums = drums, bass = bass, other = other, vocals = vocals,
                sampleRate = StemConfig.SAMPLE_RATE, frameCount = totalFrames
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.error("Engine", "SEPARATE_CRASHED", e)
        } finally {
            stemFiles.forEach { it.delete() }; winFile.delete()
        }
        result
        }
    }

    private fun computeNumChunks(totalFrames: Int): Int {
        if (totalFrames <= StemConfig.CHUNK_SAMPLES) return 1
        return ceil(
            (totalFrames - StemConfig.OVERLAP_SAMPLES).toDouble() / StemConfig.HOP_SAMPLES
        ).toInt()
    }

    private fun mmapFloat(file: File): FloatBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val mbb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            return mbb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        }
    }

    private fun hannWindow(size: Int): FloatArray =
        FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
}

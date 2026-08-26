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
import kotlinx.coroutines.delay
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
    @Volatile private var session: OrtSession? = null
    @Volatile var lastError: String? = null
    private val inferenceMutex = Mutex()
    private val nativeInflight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var releasedFlag = false
    @Volatile private var parallelInference = false
    @Volatile var throttled = false
    @Volatile private var cpuSessions: List<OrtSession> = emptyList()

    fun initialize(onDownloadProgress: (Float) -> Unit = {}): Boolean {

        return try {
            runCatching {
                context.cacheDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("sep_")) f.delete()
                }
            }

            env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Throwable) {
                throw e
            }

            val modelFile = File(context.cacheDir, "htdemucs_fp16weights.onnx")


            if (modelFile.exists() && modelFile.length() >= 150_000_000L) {
            } else {
                if (modelFile.exists()) {
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
                        }
                    }
                    gotModel = true
                } catch (e: Exception) {
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

            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
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
        val tmpFile = File(file.absolutePath + ".download")
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val existing = if (tmpFile.exists()) tmpFile.length() else 0L
                if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
                conn.connect()
                val code = conn.responseCode
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                val resuming = code == 206 && existing > 0L
                val totalBytes = if (conn.contentLengthLong > 0) {
                    conn.contentLengthLong + if (resuming) existing else 0L
                } else -1L

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tmpFile, resuming).use { output ->
                        val buf = ByteArray(65536)
                        var read: Int
                        var total = if (resuming) existing else 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            total += read
                            if (totalBytes > 0) onProgress(total.toFloat() / totalBytes)
                        }
                    }
                }
                if (!tmpFile.exists() || tmpFile.length() < 150_000_000L) {
                    throw RuntimeException("Download incomplete: ${if (tmpFile.exists()) tmpFile.length() else 0} bytes")
                }
                if (file.exists()) file.delete()
                if (!tmpFile.renameTo(file)) throw RuntimeException("Rename to model file failed")
                return
            } catch (e: Throwable) {
                lastError = e
                try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
            } finally {
                conn?.disconnect()
            }
        }
        throw RuntimeException("Model download failed after retries: ${lastError?.message ?: "unknown"}")
    }

    fun release() {
        releasedFlag = true
        var waitedMs = 0L
        while (nativeInflight.get() > 0 && waitedMs < 5000L) {
            Thread.sleep(50)
            waitedMs += 50
        }
        val sessionsToClose = LinkedHashSet<OrtSession>()
        sessionsToClose.addAll(cpuSessions)
        session?.let { sessionsToClose.add(it) }
        sessionsToClose.forEach { runCatching { it.close() } }
        cpuSessions = emptyList()
        session = null
    }

    fun isLoaded(): Boolean = session != null

    suspend fun separate(
        stereoInterleaved: FloatArray,
        onProgress: (Float) -> Unit = {}
    ): StemResult? = withContext(Dispatchers.Default) {
        if (releasedFlag) return@withContext null
        val currentSession = session ?: return@withContext null
        val currentEnv = env ?: return@withContext null

        val totalSize = stereoInterleaved.size
        val totalFrames = totalSize / 2
        val numChunks = computeNumChunks(totalFrames)

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
                    nativeInflight.incrementAndGet()
                    try {
                        sess.run(mapOf("mix" to inputTensor))
                    } finally {
                        nativeInflight.decrementAndGet()
                    }
                } catch (e: Exception) {
                    inputTensor.close(); throw e
                }
                inputTensor.close()

                val outputTensor = resultMap["stems"].orElse(null) as? OnnxTensor
                if (outputTensor == null) {
                    val keys = resultMap.iterator().asSequence().map { it.key }.joinToString()
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
                        raf.seek(bytePos); raf.readFully(readBuf)
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
                            raf.seek(bytePos); raf.readFully(readBuf)
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
                val p = if (throttled) 1 else cpuSessions.size
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
                        if (throttled) delay(60)
                    }
                }
                if (cancelled) {
                    return@withContext null
                }
            } else {
                for (chunkIdx in 0 until numChunks) {
                    if (!isActive) {
                        return@withContext null
                    }
                    val out = inferChunk(chunkIdx, currentSession) ?: return@withContext null
                    mergeChunk(chunkIdx, out)
                    if (throttled) delay(60)
                    onProgress((chunkIdx + 1).toFloat() / numChunks)
                }
            }

            run {
                val CHUNK_FLOATS = 262144
                for (stemIdx in 0 until StemConfig.NUM_STEMS) {
                    RandomAccessFile(stemFiles[stemIdx], "rw").use { stemRaf ->
                        RandomAccessFile(winFile, "r").use { winRaf ->
                            var offset = 0L
                            while (offset < totalSize) {
                                val n = minOf(CHUNK_FLOATS.toLong(), totalSize - offset).toInt()
                                val byteLen = n * 4L
                                val stemMap = stemRaf.channel.map(FileChannel.MapMode.READ_WRITE, offset * 4L, byteLen)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                val winMap = winRaf.channel.map(FileChannel.MapMode.READ_ONLY, offset * 4L, byteLen)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                val sf = stemMap.asFloatBuffer()
                                val wf = winMap.asFloatBuffer()
                                for (i in 0 until n) {
                                    val w = maxOf(wf.get(i), 1e-3f)
                                    sf.put(i, sf.get(i) / w)
                                }
                                offset += n
                            }
                        }
                    }
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
            lastError = "separate: ${e::class.simpleName}: ${e.message ?: "(no message)"}"
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

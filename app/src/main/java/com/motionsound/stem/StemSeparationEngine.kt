package com.motionsound.stem

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
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
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

    @Volatile var lastError: String? = null
        private set
    @Volatile var backendLabel: String = ""
        private set
    @Volatile var lastChunkMs: Long = -1L
        private set
    @Volatile var gpuUsed: Boolean = false

    @Volatile var throttled: Boolean = false
        private set

    private val inferenceMutex = Mutex()
    private val nativeInflight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var releasedFlag = false
    @Volatile private var parallelInference = false
    @Volatile private var interpreters: List<Interpreter> = emptyList()
    private var gpuDelegate: GpuDelegate? = null
    private var modelFd: FileInputStream? = null

    fun initialize(gpuPreferred: Boolean): Boolean {
        return try {
            runCatching {
                context.cacheDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("sep_")) f.delete()
                }
            }

            val afd = context.assets.openFd(StemConfig.MODEL_ASSET_PATH)
            if (afd.length < StemConfig.MODEL_MIN_BYTES) {
                throw RuntimeException("Model asset incomplete: ${afd.length} bytes")
            }
            val fis = FileInputStream(afd.parcelFileDescriptor.fileDescriptor)
            val mapped = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            modelFd = fis

            var gpuOk = false
            if (gpuPreferred) {
                gpuOk = tryInitGpu(mapped)
                if (!gpuOk) {
                    logBackend("GPU init failed (${lastError ?: "unknown"}) — falling back to CPU")
                    releaseInterpreters()
                }
            }

            if (gpuOk) {
                gpuUsed = true
                parallelInference = false
                backendLabel = "GPU"
            } else {
                tryInitCpu(mapped)
                gpuUsed = false
                backendLabel = "CPU x${interpreters.size}"
            }
            true
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            releaseInterpreters()
            false
        }
    }

    private fun tryInitGpu(model: ByteBuffer): Boolean {
        val delegate = try {
            GpuDelegate()
        } catch (e: Throwable) {
            lastError = "gpu-delegate: ${e::class.simpleName}: ${e.message ?: "(no message)"}"
            return false
        }
        var itp: Interpreter? = null
        try {
            val opts = Interpreter.Options()
            opts.setNumThreads(1)
            opts.addDelegate(delegate)
            itp = Interpreter(model, opts)

            val inBuf = ByteBuffer.allocateDirect(StemConfig.NUM_CHANNELS * StemConfig.CHUNK_SAMPLES * 4)
                .order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(
                StemConfig.NUM_STEMS * StemConfig.NUM_CHANNELS * StemConfig.CHUNK_SAMPLES * 4
            ).order(ByteOrder.nativeOrder())

            nativeInflight.incrementAndGet()
            try {
                itp.runForMultipleInputsOutputs(arrayOf<Any>(inBuf.rewind()), mapOf(0 to outBuf.rewind()))
            } finally {
                nativeInflight.decrementAndGet()
            }

            val fb = outBuf.asFloatBuffer()
            var sumSq = 0.0
            var finite = true
            val n = fb.remaining()
            var i = 0
            while (i < n) {
                val v = fb.get(i)
                if (v.isNaN() || v.isInfinite()) { finite = false; break }
                sumSq += v.toDouble() * v.toDouble()
                i += 4096
            }
            if (!finite || sumSq <= 0.0) {
                lastError = "GPU warmup produced degenerate output"
                runCatching { itp.close() }
                runCatching { delegate.close() }
                return false
            }
            interpreters = listOf(itp)
            gpuDelegate = delegate
            return true
        } catch (e: Throwable) {
            lastError = "gpu: ${e::class.simpleName}: ${e.message ?: "(no message)"}"
            runCatching { itp?.close() }
            runCatching { delegate.close() }
            return false
        }
    }

    private fun tryInitCpu(model: ByteBuffer) {
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (cores >= 8) 4 else maxOf(2, cores / 2)
        val list = mutableListOf<Interpreter>()
        try {
            list.add(makeCpuInterpreter(model, threads))
            if (cores >= 8) list.add(makeCpuInterpreter(model, threads))
            interpreters = list
            parallelInference = list.size > 1
        } catch (e: Throwable) {
            list.forEach { runCatching { it.close() } }
            interpreters = emptyList()
            val single = makeCpuInterpreter(model, maxOf(2, cores / 2))
            interpreters = listOf(single)
            parallelInference = false
        }
    }

    private fun makeCpuInterpreter(model: ByteBuffer, threads: Int): Interpreter {
        val opts = Interpreter.Options()
        opts.setNumThreads(threads)
        return Interpreter(model, opts)
    }

    fun release() {
        releasedFlag = true
        var waitedMs = 0L
        while (nativeInflight.get() > 0 && waitedMs < 5000L) {
            Thread.sleep(50)
            waitedMs += 50
        }
        releaseInterpreters()
        runCatching { modelFd?.close() }
        modelFd = null
    }

    private fun releaseInterpreters() {
        interpreters.forEach { runCatching { it.close() } }
        interpreters = emptyList()
        gpuDelegate?.let { runCatching { it.close() } }
        gpuDelegate = null
        parallelInference = false
    }

    fun isLoaded(): Boolean = interpreters.isNotEmpty()

    suspend fun separate(
        stereoInterleaved: FloatArray,
        onProgress: (Float) -> Unit = {}
    ): StemResult? = withContext(Dispatchers.Default) {
        if (releasedFlag) return@withContext null
        val sessions = interpreters
        if (sessions.isEmpty()) return@withContext null

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

            fun inferChunk(chunkIdx: Int, itp: Interpreter): FloatArray? {
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

                val inBuf = ByteBuffer.allocateDirect(chunkInput.size * 4)
                    .order(ByteOrder.nativeOrder())
                inBuf.asFloatBuffer().put(chunkInput)

                val outBuf = ByteBuffer.allocateDirect(
                    StemConfig.NUM_STEMS * StemConfig.NUM_CHANNELS * S2 * 4
                ).order(ByteOrder.nativeOrder())

                nativeInflight.incrementAndGet()
                val t0 = System.nanoTime()
                try {
                    itp.runForMultipleInputsOutputs(arrayOf<Any>(inBuf.rewind()), mapOf(0 to outBuf.rewind()))
                } finally {
                    nativeInflight.decrementAndGet()
                    lastChunkMs = (System.nanoTime() - t0) / 1_000_000L
                }

                val fb = outBuf.asFloatBuffer()
                val chunkOut = FloatArray(fb.remaining())
                fb.get(chunkOut)
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

            if (parallelInference && sessions.size > 1 && numChunks > 1) {
                val p = if (throttled) 1 else sessions.size
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
                                val sess = sessions[off % sessions.size]
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
                val primary = sessions[0]
                for (chunkIdx in 0 until numChunks) {
                    if (!isActive) {
                        return@withContext null
                    }
                    val out = inferChunk(chunkIdx, primary) ?: return@withContext null
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

    private fun logBackend(msg: String) {
        android.util.Log.w("StemSeparationEngine", msg)
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

package com.motionsound.stem

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.min

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

/**
 * Streaming output sink for separated stems. Implementations receive one
 * writable FloatBuffer per stem sized [frameCount] * 2 floats (interleaved
 * stereo, LITTLE_ENDIAN). The engine writes each chunk's samples as soon as
 * they are produced, so full-song stem data never has to live on the heap.
 */
interface StemOutput {
    fun openBuffer(stemIndex: Int, frameCount: Int): FloatBuffer
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

    private val inferenceMutex = Mutex()
    @Volatile private var releasedFlag = false
    @Volatile private var stemInterpreters: Array<Interpreter?> = arrayOfNulls(4)
    @Volatile private var stemFds: Array<FileInputStream?> = arrayOfNulls(4)
    private var gpuDelegate: GpuDelegate? = null

    fun initialize(gpuPreferred: Boolean): Boolean {
        return try {
            val configs = arrayOf(
                StemConfig.DRUMS,
                StemConfig.BASS,
                StemConfig.OTHER,
                StemConfig.VOCALS
            )

            var anyLoaded = false
            for (i in 0 until 4) {
                try {
                    val loaded = loadModel(i, configs[i], gpuPreferred)
                    if (loaded) anyLoaded = true
                } catch (e: Throwable) {
                    lastError = "model[$i]: ${e::class.simpleName}: ${e.message}"
                }
            }

            if (anyLoaded) {
                val loadedCount = stemInterpreters.count { it != null }
                gpuUsed = gpuDelegate != null
                backendLabel = if (gpuUsed) "GPU" else "CPU x$loadedCount"
            }
            anyLoaded
        } catch (e: Throwable) {
            lastError = e::class.simpleName + ": " + (e.message ?: "(no message)")
            releaseInterpreters()
            false
        }
    }

    private fun loadModel(stemIdx: Int, config: StemConfig.StemModelConfig, gpuPreferred: Boolean): Boolean {
        val afd = context.assets.openFd(config.assetPath)
        val fis = FileInputStream(afd.parcelFileDescriptor.fileDescriptor)
        val mapped = fis.channel.map(
            FileChannel.MapMode.READ_ONLY,
            afd.startOffset,
            afd.declaredLength
        )
        stemFds[stemIdx] = fis

        var itp: Interpreter? = null
        if (gpuPreferred && gpuDelegate == null) {
            try {
                val delegate = GpuDelegate()
                val opts = Interpreter.Options()
                opts.setNumThreads(1)
                opts.addDelegate(delegate)
                itp = Interpreter(mapped, opts)
                if (verifyWarmup(itp, config)) {
                    gpuDelegate = delegate
                    stemInterpreters[stemIdx] = itp
                    return true
                }
                runCatching { itp.close() }
                runCatching { delegate.close() }
            } catch (_: Throwable) {
                runCatching { itp?.close() }
            }
        }

        if (itp == null || stemInterpreters[stemIdx] == null) {
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = if (cores >= 8) 4 else maxOf(2, cores / 2)
            val opts = Interpreter.Options()
            opts.setNumThreads(threads)
            itp = Interpreter(mapped, opts)
            stemInterpreters[stemIdx] = itp
        }
        return true
    }

    private fun verifyWarmup(itp: Interpreter, config: StemConfig.StemModelConfig): Boolean {
        return try {
            val inputSize = 4 * config.dimF * config.dimT
            val inBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            itp.runForMultipleInputsOutputs(
                arrayOf<Any>(inBuf.rewind()),
                mapOf(0 to outBuf.rewind())
            )
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
            finite && sumSq > 0.0
        } catch (_: Throwable) {
            false
        }
    }

    fun release() {
        releasedFlag = true
        releaseInterpreters()
        for (i in 0 until 4) {
            runCatching { stemFds[i]?.close() }
            stemFds[i] = null
        }
    }

    private fun releaseInterpreters() {
        for (i in 0 until 4) {
            runCatching { stemInterpreters[i]?.close() }
            stemInterpreters[i] = null
        }
        runCatching { gpuDelegate?.close() }
        gpuDelegate = null
    }

    fun isLoaded(): Boolean = stemInterpreters.any { it != null }

    /**
     * Separates [stereoInterleaved] into 4 stems.
     *
     * When [output] is provided, stem samples are streamed into the sink's
     * buffers as each chunk completes; peak heap usage stays at roughly one
     * chunk (~12 s of audio) instead of the whole song. When null, the result
     * buffers are allocated in memory (legacy behavior, one stem at a time).
     */
    suspend fun separate(
        stereoInterleaved: FloatArray,
        output: StemOutput? = null,
        onProgress: (Float) -> Unit = {}
    ): StemResult? = withContext(Dispatchers.Default) {
        if (releasedFlag) return@withContext null

        val totalFrames = stereoInterleaved.size / 2
        val configs = arrayOf(StemConfig.DRUMS, StemConfig.BASS, StemConfig.OTHER, StemConfig.VOCALS)

        inferenceMutex.withLock {
            var result: StemResult? = null
            try {
                // With a sink, buffers live in files (mmap'd) and are never on the heap.
                val heapBuffers: Array<ByteBuffer>? = if (output == null) {
                    Array(4) {
                        ByteBuffer.allocateDirect(totalFrames * 2 * 4).order(ByteOrder.nativeOrder())
                    }
                } else null

                for (stemIdx in 0 until 4) {
                    if (!isActive) return@withContext null
                    val itp = stemInterpreters[stemIdx] ?: continue
                    val config = configs[stemIdx]

                    val chunkSize = config.chunkSize
                    val numChunks = ceil(totalFrames.toDouble() / chunkSize).toInt().coerceAtLeast(1)

                    val stemOut: FloatBuffer = if (output != null) {
                        output.openBuffer(stemIdx, totalFrames)
                    } else {
                        val hb = heapBuffers!![stemIdx]
                        hb.rewind()
                        hb.asFloatBuffer()
                    }

                    val inputSize = 4 * config.dimF * config.dimT
                    // reused across chunks: one direct input/output buffer per stem
                    val inBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
                    val outBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())

                    for (chunkIdx in 0 until numChunks) {
                        if (!isActive) return@withContext null
                        val start = chunkIdx * chunkSize
                        val end = min(start + chunkSize, totalFrames)
                        val actualChunk = end - start

                        // reflect padding must wrap around THIS chunk's own edges,
                        // so always isolate the chunk's samples first
                        val chunkInterleaved = stereoInterleaved.copyOfRange(start * 2, end * 2)

                        val stftData = StftProcessor.stft(
                            chunkInterleaved,
                            config.nFft,
                            StemConfig.HOP_LENGTH,
                            config.dimF,
                            config.dimT
                        )

                        var idx = 0
                        for (c in 0 until 4) {
                            val src = stftData[c]
                            for (f in 0 until config.dimF) {
                                val row = f * config.dimT
                                for (t in 0 until config.dimT) {
                                    inBuf.putFloat(idx * 4, src[row + t])
                                    idx++
                                }
                            }
                        }

                        inBuf.rewind()
                        outBuf.rewind()

                        val t0 = System.nanoTime()
                        itp.runForMultipleInputsOutputs(
                            arrayOf<Any>(inBuf),
                            mapOf(0 to outBuf)
                        )
                        lastChunkMs = (System.nanoTime() - t0) / 1_000_000L

                        val outArr = Array(4) { c ->
                            FloatArray(config.dimF * config.dimT) { i ->
                                outBuf.getFloat((c * config.dimF * config.dimT + i) * 4)
                            }
                        }

                        val istftResult = StftProcessor.istft(
                            outArr,
                            config.nFft,
                            StemConfig.HOP_LENGTH,
                            config.nBins,
                            actualChunk,
                            config.dimF,
                            config.dimT
                        )

                        stemOut.position(start * 2)
                        stemOut.put(istftResult, 0, actualChunk * 2)

                        onProgress((stemIdx.toFloat() + (chunkIdx + 1).toFloat() / numChunks) / 4f)
                    }
                }

                result = if (output != null) {
                    StemResult(
                        drums = output.openBuffer(StemConfig.STEM_DRUMS, totalFrames).apply { rewind() },
                        bass = output.openBuffer(StemConfig.STEM_BASS, totalFrames).apply { rewind() },
                        other = output.openBuffer(StemConfig.STEM_OTHER, totalFrames).apply { rewind() },
                        vocals = output.openBuffer(StemConfig.STEM_VOCALS, totalFrames).apply { rewind() },
                        sampleRate = StemConfig.SAMPLE_RATE,
                        frameCount = totalFrames
                    )
                } else {
                    fun fb(buf: ByteBuffer): FloatBuffer {
                        buf.rewind()
                        return buf.asFloatBuffer()
                    }
                    StemResult(
                        drums = fb(heapBuffers!![0]),
                        bass = fb(heapBuffers[1]),
                        other = fb(heapBuffers[2]),
                        vocals = fb(heapBuffers[3]),
                        sampleRate = StemConfig.SAMPLE_RATE,
                        frameCount = totalFrames
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = "separate: ${e::class.simpleName}: ${e.message ?: "(no message)"}"
            }
            result
        }
    }
}

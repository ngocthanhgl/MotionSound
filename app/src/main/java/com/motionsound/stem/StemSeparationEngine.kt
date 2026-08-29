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
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.min

data class StemResult(
    val drums: java.nio.FloatBuffer,
    val bass: java.nio.FloatBuffer,
    val other: java.nio.FloatBuffer,
    val vocals: java.nio.FloatBuffer,
    val sampleRate: Int,
    val frameCount: Int
) {
    fun stemAt(index: Int): java.nio.FloatBuffer = when (index) {
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

    private val inferenceMutex = Mutex()
    @Volatile private var releasedFlag = false
    @Volatile private var stemInterpreters: Array<Interpreter?> = arrayOfNulls(4)
    @Volatile private var stemFds: Array<FileInputStream?> = arrayOfNulls(4)
    private var gpuDelegate: GpuDelegate? = null

    fun initialize(gpuPreferred: Boolean): Boolean {
        return try {
            try {
                context.cacheDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("sep_")) f.delete()
                }
            } catch (_: Throwable) {}

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

    suspend fun separate(
        stereoInterleaved: FloatArray,
        onProgress: (Float) -> Unit = {}
    ): StemResult? = withContext(Dispatchers.Default) {
        if (releasedFlag) return@withContext null

        val totalFrames = stereoInterleaved.size / 2
        val configs = arrayOf(StemConfig.DRUMS, StemConfig.BASS, StemConfig.OTHER, StemConfig.VOCALS)

        inferenceMutex.withLock {
            var result: StemResult? = null
            try {
                val stemBuffers = Array(4) { java.nio.ByteBuffer.allocateDirect(totalFrames * 2 * 4).order(ByteOrder.nativeOrder()) }

                for (stemIdx in 0 until 4) {
                    if (!isActive) return@withContext null
                    val itp = stemInterpreters[stemIdx] ?: continue
                    val config = configs[stemIdx]

                    val chunkSize = config.chunkSize
                    val numChunks = ceil(totalFrames.toDouble() / chunkSize).toInt().coerceAtLeast(1)

                    val stemOutput = FloatArray(totalFrames * 2)
                    val winAccum = FloatArray(totalFrames * 2)

                    for (chunkIdx in 0 until numChunks) {
                        if (!isActive) return@withContext null
                        val start = chunkIdx * chunkSize
                        val end = min(start + chunkSize, totalFrames)
                        val actualChunk = end - start

                        val chunkInterleaved = FloatArray(actualChunk * 2)
                        for (f in 0 until actualChunk) {
                            chunkInterleaved[f * 2] = stereoInterleaved[(start + f) * 2]
                            chunkInterleaved[f * 2 + 1] = stereoInterleaved[(start + f) * 2 + 1]
                        }

                        val stftData = StftProcessor.stft(
                            chunkInterleaved,
                            config.nFft,
                            StemConfig.HOP_LENGTH,
                            config.dimF,
                            config.dimT
                        )

                        val inputSize = 4 * config.dimF * config.dimT
                        val inBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
                        var idx = 0
                        for (c in 0 until 4) {
                            for (f in 0 until config.dimF) {
                                for (t in 0 until config.dimT) {
                                    inBuf.putFloat(idx * 4, stftData[c][f * config.dimT + t])
                                    idx++
                                }
                            }
                        }

                        val outBuf = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())

                        val t0 = System.nanoTime()
                        itp.runForMultipleInputsOutputs(
                            arrayOf<Any>(inBuf.rewind()),
                            mapOf(0 to outBuf.rewind())
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
                            actualChunk
                        )

                        for (f in 0 until actualChunk) {
                            stemOutput[(start + f) * 2] += istftResult[f * 2]
                            stemOutput[(start + f) * 2 + 1] += istftResult[f * 2 + 1]
                            winAccum[(start + f) * 2] += 1f
                            winAccum[(start + f) * 2 + 1] += 1f
                        }

                        onProgress((stemIdx.toFloat() + (chunkIdx + 1).toFloat() / numChunks) / 4f)
                    }

                    for (i in stemOutput.indices) {
                        if (winAccum[i] > 1e-8f) {
                            stemOutput[i] /= winAccum[i]
                        }
                    }

                    val buf = stemBuffers[stemIdx]
                    buf.rewind()
                    for (i in stemOutput) {
                        buf.putFloat(i)
                    }
                }

                val mkFb: (java.nio.ByteBuffer) -> java.nio.FloatBuffer = { buf ->
                    buf.rewind()
                    buf.asFloatBuffer()
                }

                result = StemResult(
                    drums = mkFb(stemBuffers[0]),
                    bass = mkFb(stemBuffers[1]),
                    other = mkFb(stemBuffers[2]),
                    vocals = mkFb(stemBuffers[3]),
                    sampleRate = StemConfig.SAMPLE_RATE,
                    frameCount = totalFrames
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = "separate: ${e::class.simpleName}: ${e.message ?: "(no message)"}"
            }
            result
        }
    }
}

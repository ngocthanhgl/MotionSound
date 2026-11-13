package com.motionsound.stem

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

class StemCache(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, StemConfig.CACHE_DIR).also { it.mkdirs() }

    fun hasCachedStems(uri: Uri): Boolean {
        val key = hashKey(uri)
        return StemConfig.STEM_NAMES.all { name ->
            File(cacheDir, "${key}_$name.raw").exists()
        }
    }

    fun loadStems(uri: Uri): StemResult? {
        val key = hashKey(uri)
        return try {
            val drums = load(key, "drums")
            val bass = load(key, "bass")
            val other = load(key, "other")
            val vocals = load(key, "vocals")
            val result = StemResult(
                drums = drums,
                bass = bass,
                other = other,
                vocals = vocals,
                sampleRate = StemConfig.SAMPLE_RATE,
                frameCount = drums.remaining() / 2
            )
            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens a streaming write session for [uri]. The separation engine writes
     * each stem chunk directly into mmap'd temp files, so the heap never holds
     * full-song stem buffers. [commit] promotes the temp files to the final
     * cache entries atomically.
     */
    fun openWriteSession(uri: Uri, frameCount: Int): WriteSession {
        return WriteSession(hashKey(uri), frameCount)
    }

    inner class WriteSession(private val key: String, private val frameCount: Int) : StemOutput {

        private val tmpFiles = arrayOfNulls<File>(4)
        private val buffers = arrayOfNulls<FloatBuffer>(4)
        @Volatile private var aborted = false

        override fun openBuffer(stemIndex: Int, expectedFrames: Int): FloatBuffer {
            require(expectedFrames == frameCount) {
                "frameCount mismatch: expected $frameCount, got $expectedFrames"
            }
            buffers[stemIndex]?.let { return it }
            check(!aborted) { "session aborted" }
            val bytes = frameCount * 2L * 4L
            val name = StemConfig.STEM_NAMES[stemIndex]
            val tmp = File(cacheDir, "${key}_$name.tmp")
            if (tmp.exists()) tmp.delete()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(bytes)
                val mbb = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
                mbb.order(ByteOrder.LITTLE_ENDIAN)
                tmpFiles[stemIndex] = tmp
                val fb = mbb.asFloatBuffer()
                buffers[stemIndex] = fb
                return fb
            }
        }

        /** Promotes all 4 temp files to final cache entries. Returns true on full success. */
        fun commit(): Boolean {
            if (aborted) return false
            var ok = true
            for (i in 0 until 4) {
                val tmp = tmpFiles[i]
                if (tmp == null) {
                    ok = false
                    continue
                }
                val name = StemConfig.STEM_NAMES[i]
                val target = File(cacheDir, "${key}_$name.raw")
                if (target.exists()) target.delete()
                val moved = tmp.length() == frameCount * 2L * 4L && tmp.renameTo(target)
                if (!moved) tmp.delete()
                if (!moved) ok = false
            }
            if (ok) {
                enforceCap()
            } else {
                // partial failure: promote none, clean every temp file
                for (f in tmpFiles) f?.delete()
            }
            aborted = !ok
            return ok
        }

        fun abort() {
            if (aborted) return
            aborted = true
            for (f in tmpFiles) f?.delete()
        }
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Best-effort removal of legacy cache dirs (pre-v2 stems came from a
     * different model generation; loading them silently produces garbage).
     */
    fun purgeLegacy() {
        runCatching { File(context.cacheDir, "stems_cache").deleteRecursively() }
    }

    fun cacheSize(): Long {
        val size = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        return size
    }

    private fun hashKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(uri.toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun enforceCap() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".raw") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun load(key: String, name: String): FloatBuffer {
        val file = File(cacheDir, "${key}_$name.raw")
        if (!file.exists() || file.length() % 4 != 0L) {
            throw RuntimeException("Corrupt cache file: ${file.absolutePath} size=${file.length()}")
        }
        val count = (file.length() / 4).toInt()
        RandomAccessFile(file, "r").use { raf ->
            val mbb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            return mbb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        }
    }

    companion object {
        private const val TAG = "StemCache"
        private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024
    }
}

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

    fun saveStems(uri: Uri, result: StemResult): Boolean {
        val key = hashKey(uri)
        var ok = true
        ok = save(key, "drums", result.drums) && ok
        ok = save(key, "bass", result.bass) && ok
        ok = save(key, "other", result.other) && ok
        ok = save(key, "vocals", result.vocals) && ok
        if (ok) enforceCap()
        return ok
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

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
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

    private fun save(key: String, name: String, data: FloatBuffer): Boolean {
        val target = File(cacheDir, "${key}_$name.raw")
        val tmp = File(cacheDir, "${key}_$name.tmp")
        data.mark()
        try {
            val count = data.remaining()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(count * 4L)
                val mbb = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, count * 4L)
                mbb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(data)
                mbb.force()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                android.util.Log.w(TAG, "Atomic rename failed: ${target.name}")
                return false
            }
            return true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Save failed: $name (${e::class.simpleName}: ${e.message})")
            tmp.delete()
            return false
        } finally {
            data.reset()
        }
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

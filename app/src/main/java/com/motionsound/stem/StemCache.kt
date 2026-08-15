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

    fun hasCachedStems(uri: Uri, silent: Boolean = false): Boolean {
        val key = hashKey(uri)
        val exists = StemConfig.STEM_NAMES.all { name ->
            File(cacheDir, "${key}_$name.raw").exists()
        }
        if (!silent) AppLogger.i("StemCache", "hasCached key=$key -> $exists")
        return exists
    }

    fun saveStems(uri: Uri, result: StemResult) {
        val key = hashKey(uri)
        AppLogger.event("StemCache", "SAVE", "key=$key")
        try {
            save(key, "drums", result.drums)
            save(key, "bass", result.bass)
            save(key, "other", result.other)
            save(key, "vocals", result.vocals)
            AppLogger.i("StemCache", "Saved 4 stems total=${result.drums.remaining() * 4 * 4 / 1024 / 1024}MB")
        } catch (e: Exception) {
            AppLogger.w("StemCache", "Save failed: ${e.message}")
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
            AppLogger.event("StemCache", "LOAD_OK", "key=$key frames=${drums.remaining() / 2}")
            result
        } catch (e: Exception) {
            AppLogger.w("StemCache", "Load failed key=$key: ${e.message}")
            null
        }
    }

    fun clearAll() {
        AppLogger.event("StemCache", "CLEAR_ALL")
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun cacheSize(): Long {
        val size = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        AppLogger.i("StemCache", "cacheSize=${size / 1024 / 1024}MB")
        return size
    }

    private fun hashKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(uri.toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun save(key: String, name: String, data: FloatBuffer) {
        try {
            val file = File(cacheDir, "${key}_$name.raw")
            val count = data.remaining()
            data.mark()
            randomAccessWrite(file, count, data)
            data.reset()
        } catch (e: Exception) {
            AppLogger.w("StemCache", "save $key/$name failed: ${e.message}")
        }
    }

    private fun randomAccessWrite(file: File, floatCount: Int, data: FloatBuffer) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(floatCount * 4L)
            val mbb = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, floatCount * 4L)
            val target = mbb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            target.put(data)
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
}

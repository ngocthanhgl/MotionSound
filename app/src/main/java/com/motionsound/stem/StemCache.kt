package com.motionsound.stem

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class StemCache(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, StemConfig.CACHE_DIR).also { it.mkdirs() }

    fun hasCachedStems(uri: Uri): Boolean {
        val key = hashKey(uri)
        val exists = StemConfig.STEM_NAMES.all { name ->
            File(cacheDir, "${key}_$name.raw").exists()
        }
        AppLogger.i("StemCache", "hasCached key=$key -> $exists")
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
            AppLogger.i("StemCache", "Saved 4 stems total=${result.drums.size * 4 * 4 / 1024 / 1024}MB")
        } catch (e: Exception) {
            AppLogger.w("StemCache", "Save failed: ${e.message}")
        }
    }

    fun loadStems(uri: Uri): StemResult? {
        val key = hashKey(uri)
        return try {
            val result = StemResult(
                drums = load(key, "drums"),
                bass = load(key, "bass"),
                other = load(key, "other"),
                vocals = load(key, "vocals"),
                sampleRate = StemConfig.SAMPLE_RATE
            )
            AppLogger.event("StemCache", "LOAD_OK", "key=$key frames=${result.drums.size / 2}")
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

    private fun save(key: String, name: String, data: FloatArray) {
        try {
            val file = File(cacheDir, "${key}_$name.raw")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
                data.forEach { dos.writeFloat(it) }
            }
        } catch (e: Exception) {
            AppLogger.w("StemCache", "save $key/$name failed: ${e.message}")
        }
    }

    private fun load(key: String, name: String): FloatArray {
        val file = File(cacheDir, "${key}_$name.raw")
        if (!file.exists() || file.length() % 4 != 0L) {
            throw RuntimeException("Corrupt cache file: ${file.absolutePath} size=${file.length()}")
        }
        val count = (file.length() / 4).toInt()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
            FloatArray(count) { dis.readFloat() }
        }
    }
}

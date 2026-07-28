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
        return StemConfig.STEM_NAMES.all { name ->
            File(cacheDir, "${key}_$name.raw").exists()
        }
    }

    fun saveStems(uri: Uri, result: StemResult) {
        val key = hashKey(uri)
        save(key, "drums", result.drums)
        save(key, "bass", result.bass)
        save(key, "other", result.other)
        save(key, "vocals", result.vocals)
    }

    fun loadStems(uri: Uri): StemResult? {
        val key = hashKey(uri)
        return try {
            StemResult(
                drums = load(key, "drums"),
                bass = load(key, "bass"),
                other = load(key, "other"),
                vocals = load(key, "vocals"),
                sampleRate = StemConfig.SAMPLE_RATE
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearAll() = cacheDir.listFiles()?.forEach { it.delete() }

    fun cacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun hashKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(uri.toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun save(key: String, name: String, data: FloatArray) {
        val file = File(cacheDir, "${key}_$name.raw")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
            data.forEach { dos.writeFloat(it) }
        }
    }

    private fun load(key: String, name: String): FloatArray {
        val file = File(cacheDir, "${key}_$name.raw")
        val count = (file.length() / 4).toInt()
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
            FloatArray(count) { dis.readFloat() }
        }
    }
}

package com.motionsound.stem

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AppLogger {

    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_FILES = 3
    private const val FLUSH_MS = 1000L
    private const val BUFFER_BYTES = 64 * 1024

    private var logFile: File? = null
    private var publicFile: File? = null
    private var initialized = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val buffer = StringBuilder(BUFFER_BYTES)
    private val throttleMap = ConcurrentHashMap<String, Long>()

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AppLogger").apply { priority = Thread.MIN_PRIORITY }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        logFile = File(context.cacheDir, "motionsound_debug.log")
        publicFile = try {
            File("/storage/emulated/0/Download/motionsound_debug.log")
        } catch (_: Exception) { null }
        io.scheduleWithFixedDelay({ flush() }, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)
        val sep = "=".repeat(50)
        i("AppLogger", sep)
        i("AppLogger", "APP STARTED")
        i("AppLogger", "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        i("AppLogger", "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        i("AppLogger", "API: ${Build.VERSION.SDK_INT}")
        i("AppLogger", "Cache: ${context.cacheDir.absolutePath}")
        i("AppLogger", "Public: ${publicFile?.absolutePath ?: "N/A"}")
        i("AppLogger", sep)
    }

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    fun throttled(tag: String, key: String, minMs: Long, msg: String) {
        val now = System.currentTimeMillis()
        val last = throttleMap.getOrDefault(key, 0L)
        if (now - last < minMs) return
        throttleMap[key] = now
        log("I", tag, msg)
    }

    fun event(tag: String, event: String, detail: String = "") {
        val msg = if (detail.isEmpty()) "[$event]" else "[$event] $detail"
        log("I", tag, msg)
    }

    fun error(tag: String, msg: String, th: Throwable? = null) {
        log("E", tag, msg)
        th?.let {
            log("E", tag, "  ${it::class.java.name}: ${it.message ?: "(no message)"}")
            for (s in it.stackTrace.take(8)) {
                log("E", tag, "  at ${s.className}.${s.methodName}(${s.fileName}:${s.lineNumber})")
            }
        }
    }

    private fun log(level: String, tag: String, msg: String) {
        val ts = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "$ts [$thread] $level/$tag: $msg\n"
        var needFlush = false
        synchronized(buffer) {
            buffer.append(line)
            needFlush = buffer.length >= BUFFER_BYTES
        }
        if (needFlush) io.execute { flush() }
        Log.d("MotionSound/$tag", msg)
    }

    private fun flush() {
        val data: String
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            data = buffer.toString()
            buffer.setLength(0)
        }
        if (logFile != null) {
            try {
                rotateIfNeeded(logFile!!)
                logFile!!.appendText(data)
            } catch (_: Exception) {
            }
        }
        if (publicFile != null) {
            try {
                rotateIfNeeded(publicFile!!)
                publicFile!!.appendText(data)
            } catch (_: Exception) {
                publicFile = null
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_FILE_BYTES) return
        val dir = file.parentFile ?: return
        for (i in MAX_FILES - 2 downTo 1) {
            File(dir, file.name + "." + (i + 1)).delete()
            val cur = File(dir, file.name + "." + i)
            if (cur.exists()) cur.renameTo(File(dir, file.name + "." + (i + 1)))
        }
        file.renameTo(File(dir, file.name + ".1"))
    }

    fun getLogFile(): File? = logFile
}

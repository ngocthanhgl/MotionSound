package com.motionsound.stem

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private var logFile: File? = null
    private var publicFile: File? = null
    private var initialized = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        logFile = File(context.cacheDir, "motionsound_debug.log")
        publicFile = try {
            File("/storage/emulated/0/Download/motionsound_debug.log")
        } catch (_: Exception) { null }
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
        try { logFile?.appendText(line) } catch (_: Exception) {}
        try { publicFile?.appendText(line) } catch (_: Exception) {}
        Log.d("MotionSound/$tag", msg)
    }

    fun getLogFile(): File? = logFile
}

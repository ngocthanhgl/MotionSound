package com.motionsound.stem

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogger(private val context: Context) {

    private val logFile = File(context.cacheDir, "motionsound_debug.log")
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        logFile.bufferedWriter().use { it.write("") }
        log("DebugLogger", "Log started at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        log("DebugLogger", "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("DebugLogger", "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        log("DebugLogger", "API: ${Build.VERSION.SDK_INT}")
        log("DebugLogger", "Cache dir: ${context.cacheDir.absolutePath}")
    }

    fun log(step: String, detail: String) {
        val ts = dateFormat.format(Date())
        val line = "$ts [$step] $detail\n"
        try {
            logFile.appendText(line)
        } catch (_: Exception) {}
        Log.d("MotionSoundDebug", "$step: $detail")
    }

    fun log(step: String, error: Throwable) {
        log(step, "${error::class.simpleName}: ${error.message}")
        for ((i, s) in error.stackTrace.take(5).withIndex()) {
            log(step, "  at ${s.className}.${s.methodName}(${s.fileName}:${s.lineNumber})")
        }
    }

    fun getLogFile(): File = logFile
}

package com.motionsound.util

import android.content.Context
import java.io.File

object CrashLogger {
    private var initDir: File? = null

    fun init(context: Context) {
        initDir = File(context.filesDir, "crash_logs")
        initDir?.mkdirs()
    }

    fun log(tag: String, msg: String, t: Throwable? = null) {
        val dir = initDir ?: return
        val f = File(dir, "crash_${System.currentTimeMillis()}.txt")
        try {
            f.bufferedWriter().use { w ->
                w.write("[$tag] $msg\n")
                t?.let { w.write(it.stackTraceToString() + "\n") }
            }
        } catch (_: Exception) {}
    }
}

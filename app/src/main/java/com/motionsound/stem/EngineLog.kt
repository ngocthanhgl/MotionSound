package com.motionsound.stem

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process black-box diagnostic log. The app has no device access in the
 * field, so key engine events (model load attempts, warmup results, decode,
 * chunk progress, crashes) are kept in a bounded ring buffer and persisted to
 * a file. The Settings "AI Model" dialog shares the whole log via a plain
 * text intent, so a user can send it from any messaging app — no logcat or
 * USB needed. Because the buffer is persisted by the crash handler, it also
 * survives native crashes well enough to show the last steps before death.
 */
object EngineLog {

    private const val MAX_LINES = 300
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var sincePersist = 0

    @Synchronized
    fun log(msg: String) {
        val line = "${stamp.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        // Persist periodically: native crashes (SIGSEGV/SIGABRT) bypass the
        // Java uncaught-exception handler, so the trail must be on disk
        // before the process dies. One small write per 32 events is cheap.
        if (++sincePersist >= 32) {
            sincePersist = 0
            runCatching { dirtyPersist() }
        }
    }

    private fun dirtyPersist() {
        // caller must hold the lock
        File(contextRef?.filesDir ?: return, "engine_log.txt").writeText(dump() + "\n")
    }

    @Volatile private var contextRef: Context? = null

    /** Gives the logger a context so it can auto-persist on native crashes. */
    fun attach(context: Context) {
        contextRef = context.applicationContext
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    /** Writes the current buffer to filesDir/engine_log.txt (best effort). */
    @Synchronized
    fun persist(context: Context?) {
        if (context == null) return
        runCatching {
            File(context.filesDir, "engine_log.txt").writeText(dump() + "\n")
        }
    }

    /** Records an uncaught crash and persists before the process dies. */
    fun recordCrash(context: Context?, t: Throwable) {
        log("CRASH ${t::class.simpleName}: ${t.message ?: "(no message)"}")
        log(t.stackTraceToString().take(3000))
        persist(context)
    }

    /** Shares the persisted log (or the live buffer) via a chooser. */
    fun share(context: Context) {
        persist(context)
        val file = File(context.filesDir, "engine_log.txt")
        val text = runCatching { file.readText() }.getOrDefault(dump())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MotionSound diagnostics")
            putExtra(Intent.EXTRA_TEXT, text.take(900_000))
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }
}

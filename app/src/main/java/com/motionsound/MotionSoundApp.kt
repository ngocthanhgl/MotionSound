package com.motionsound

import android.app.Application
import android.content.Intent
import android.util.Log
import com.motionsound.stem.EngineLog
import com.motionsound.stem.StemPlayerService

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()

        EngineLog.attach(this)

        // Black-box crash capture: the uncaught-exception handler persists the
        // engine log (recent events + stack trace) before the process dies, so
        // field crashes are diagnosable from the Settings "Share diagnostics"
        // button without adb.
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { EngineLog.recordCrash(this, e) }
            platformHandler?.uncaughtException(t, e)
        }

        try {
            startForegroundService(Intent(this, StemPlayerService::class.java))
        } catch (e: Exception) {
            Log.w("MotionSoundApp", "FGS start rejected at process boot (${e::class.simpleName}); service will start from Activity/ViewModel bind")
        }
    }
}
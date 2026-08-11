package com.motionsound

import android.app.Application
import android.content.Intent
import com.motionsound.stem.AppLogger
import com.motionsound.stem.StemPlayerService

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.event("MotionSoundApp", "APP_ONCREATE")
        try {
            startForegroundService(Intent(this, StemPlayerService::class.java))
        } catch (e: Exception) {
            AppLogger.error("MotionSoundApp", "Service start failed", e)
        }
    }
}

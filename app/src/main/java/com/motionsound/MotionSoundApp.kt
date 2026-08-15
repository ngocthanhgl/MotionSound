package com.motionsound

import android.app.Application
import android.content.Intent
import com.motionsound.stem.StemPlayerService

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundService(Intent(this, StemPlayerService::class.java))
        } catch (_: Exception) {
        }
    }
}
package com.motionsound

import android.app.Application
import android.content.Intent
import android.util.Log
import com.motionsound.stem.StemPlayerService

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundService(Intent(this, StemPlayerService::class.java))
        } catch (e: Exception) {
            Log.w("MotionSoundApp", "FGS start rejected at process boot (${e::class.simpleName}); service will start from Activity/ViewModel bind")
        }
    }
}
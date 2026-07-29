package com.motionsound

import android.app.Application
import android.content.Intent
import android.util.Log
import com.motionsound.stem.StemPlayerService
import java.io.File

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            File(cacheDir, "motionsound_app_started.txt")
                .writeText("Application.onCreate() at ${System.currentTimeMillis()}\n")
            Log.d("MotionSoundApp", "Application started, cacheDir=${cacheDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("MotionSoundApp", "Failed to write startup marker", e)
        }
        try {
            File("/storage/emulated/0/Download/motionsound_app_started.txt")
                .writeText("Application.onCreate() at ${System.currentTimeMillis()}\n")
        } catch (_: Exception) {}

        try {
            startForegroundService(Intent(this, StemPlayerService::class.java))
            Log.d("MotionSoundApp", "StemPlayerService started from Application.onCreate")
        } catch (e: Exception) {
            Log.e("MotionSoundApp", "Failed to start service from Application.onCreate", e)
        }
    }
}

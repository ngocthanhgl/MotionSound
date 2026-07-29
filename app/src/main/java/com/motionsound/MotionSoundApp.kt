package com.motionsound

import android.app.Application
import android.util.Log
import java.io.File

class MotionSoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val f = File(cacheDir, "motionsound_app_started.txt")
            f.writeText("Application.onCreate() at ${System.currentTimeMillis()}\n")
            Log.d("MotionSoundApp", "Application started, cacheDir=${cacheDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("MotionSoundApp", "Failed to write startup marker", e)
        }
        try {
            val pf = File("/storage/emulated/0/Download/motionsound_app_started.txt")
            pf.writeText("Application.onCreate() at ${System.currentTimeMillis()}\n")
            Log.d("MotionSoundApp", "Public startup marker written to ${pf.absolutePath}")
        } catch (_: Exception) {}
    }
}

package com.motionsound.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
class DriveService : Service() {

    private lateinit var pipeline: DrivePipeline

    inner class LocalBinder : Binder() {
        fun getPipeline(): DrivePipeline = pipeline
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        pipeline = DrivePipeline(this)

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w("DriveService", "startForeground failed (notification permission denied)", e)
        }
        pipeline.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        pipeline.destroy()
        super.onDestroy()
    }

    fun getPipeline(): DrivePipeline = pipeline

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DRIVE_CHANNEL_ID,
                "Driving EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Adaptive EQ status"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, DRIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MotionSound — Drive")
            .setContentText("Adaptive EQ active")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 200
        const val DRIVE_CHANNEL_ID = "drive_eq"
    }
}

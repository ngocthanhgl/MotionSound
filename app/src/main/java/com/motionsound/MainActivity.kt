package com.motionsound

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motionsound.data.SoundPrefsStore
import com.motionsound.stem.StemPlayerService
import kotlinx.coroutines.launch
import com.motionsound.ui.screens.MainScreen
import com.motionsound.ui.screens.OnboardingScreen
import com.motionsound.ui.theme.MotionSoundTheme

enum class PermissionIssue {
    MEDIA, NOTIFICATIONS, LOCATION, BATTERY
}

class MainActivity : ComponentActivity() {

    private val darkState = mutableStateOf(false)
    private val permissionIssues = mutableStateOf<Set<PermissionIssue>>(emptySet())

    private fun refreshPermissions() {
        val missing = mutableSetOf<PermissionIssue>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += PermissionIssue.MEDIA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += PermissionIssue.NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += PermissionIssue.LOCATION
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) != true) {
            missing += PermissionIssue.BATTERY
        }
        permissionIssues.value = missing
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        refreshPermissions()

        lifecycleScope.launch {
            SoundPrefsStore.darkFlow(this@MainActivity).collect { darkState.value = it }
        }

        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        setContent {
            MotionSoundTheme(dark = darkState.value) {
                var showMain by remember { mutableStateOf(audioOk && notifOk && locationOk) }

                if (showMain) {
                    MainScreen(permissionIssues = permissionIssues.value)
                } else {
                    OnboardingScreen(onComplete = {
                        showMain = true
                        startForegroundService(Intent(this, StemPlayerService::class.java))
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

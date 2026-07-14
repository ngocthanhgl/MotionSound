package com.motionsound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.motionsound.data.ThemeManager
import com.motionsound.ui.screens.MainScreen
import com.motionsound.ui.screens.OnboardingScreen
import com.motionsound.ui.theme.MotionSoundTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        setContent {
            val darkMode by ThemeManager.getDarkModeFlow(this).collectAsState("system")
            val amoled by ThemeManager.getAmoledFlow(this).collectAsState(false)
            val useDarkTheme = when (darkMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            MotionSoundTheme(darkTheme = useDarkTheme, amoledMode = amoled) {
                var showMain by remember { mutableStateOf(audioOk && notifOk && locationOk) }

                if (showMain) {
                    MainScreen()
                } else {
                    OnboardingScreen(onComplete = { showMain = true })
                }
            }
        }
    }
}

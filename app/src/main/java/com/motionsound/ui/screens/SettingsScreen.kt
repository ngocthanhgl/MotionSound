package com.motionsound.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DarkMode
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.motionsound.ui.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showDarkModeDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showDevInfoDialog by remember { mutableStateOf(false) }
    var showDynamicColorDialog by remember { mutableStateOf(false) }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        "1.0.0"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = 8.dp)
        ) {
            item {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    icon = Icons.Filled.Palette,
                    title = "Dynamic Color",
                    subtitle = "Use Material You colors",
                    onClick = { showDynamicColorDialog = true }
                )
            }

            item {
                SettingsCard(
                    icon = Icons.AutoMirrored.Filled.DarkMode,
                    title = "Dark Mode",
                    subtitle = "System default",
                    onClick = { showDarkModeDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Info",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    icon = Icons.Filled.Info,
                    title = "App Info",
                    subtitle = "Version $versionName",
                    onClick = { showAppInfoDialog = true }
                )
            }

            item {
                SettingsCard(
                    icon = Icons.Filled.Code,
                    title = "Developer Info",
                    subtitle = "MotionSound Dev",
                    onClick = { showDevInfoDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showDynamicColorDialog) {
        AlertDialog(
            onDismissRequest = { showDynamicColorDialog = false },
            title = { Text("Dynamic Color") },
            text = { Text("MotionSound uses Material You dynamic colors from your wallpaper. This is automatic on Android 12+.") },
            confirmButton = {
                TextButton(onClick = { showDynamicColorDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showDarkModeDialog) {
        AlertDialog(
            onDismissRequest = { showDarkModeDialog = false },
            title = { Text("Dark Mode") },
            text = { Text("Dark mode follows your system setting. Toggle in your device settings to change between Light and Dark themes.") },
            confirmButton = {
                TextButton(onClick = { showDarkModeDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text("App Info") },
            text = {
                Column {
                    Text("MotionSound v$versionName")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A modern Material 3 music player.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showDevInfoDialog) {
        AlertDialog(
            onDismissRequest = { showDevInfoDialog = false },
            title = { Text("Developer Info") },
            text = { Text("MotionSound\nBuilt with Jetpack Compose & Material 3\nKotlin + ExoPlayer") },
            confirmButton = {
                TextButton(onClick = { showDevInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

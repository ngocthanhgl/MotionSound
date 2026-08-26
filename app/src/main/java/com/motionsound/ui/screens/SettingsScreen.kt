package com.motionsound.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.motionsound.drive.DriveViewModel
import com.motionsound.stem.StemCache
import com.motionsound.ui.components.SettingsCard
import com.motionsound.ui.theme.LocalComicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel

private fun checkBgLocationGranted(ctx: Context): Boolean {
    return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun SettingsScreen(
    driveViewModel: DriveViewModel = viewModel(),
    darkMode: Boolean = false,
    onDarkModeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveState by driveViewModel.driveState.collectAsState()
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showDevInfoDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var cacheSizeMb by remember { mutableStateOf(0L) }
    var gpuEnabled by remember { mutableStateOf(true) }

    val stemCache = remember { StemCache(context) }

    LaunchedEffect(Unit) {
        cacheSizeMb = withContext(Dispatchers.IO) { stemCache.cacheSize() / (1024 * 1024) }
        gpuEnabled = com.motionsound.data.SoundPrefsStore.isGpuEnabled(context)
    }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                val comic = LocalComicColors.current
                SettingsCard(
                    icon = ComicIcons.DarkMode,
                    title = "Dark Mode",
                    subtitle = if (darkMode) "On — comic dark, red accent" else "Off — paper light",
                    onClick = { scope.launch { onDarkModeChange(!darkMode) } },
                    trailing = {
                        Switch(
                            checked = darkMode,
                            onCheckedChange = { checked -> scope.launch { onDarkModeChange(checked) } },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = comic.yellow,
                                checkedThumbColor = comic.ink,
                                uncheckedTrackColor = comic.surfaceAlt,
                                uncheckedThumbColor = comic.ink,
                                uncheckedBorderColor = comic.ink
                            )
                        )
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "AI Stem Separation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    icon = ComicIcons.Memory,
                    title = "AI Model",
                    subtitle = if (driveState.modelLoaded && driveState.chunkMs > 0)
                        "htdemucs • ${driveState.inferenceBackend} • ${driveState.chunkMs} ms/chunk"
                    else
                        "htdemucs (on-device)",
                    onClick = { showModelDialog = true }
                )
            }

            item {
                SettingsCard(
                    icon = ComicIcons.Memory,
                    title = "GPU Acceleration",
                    subtitle = if (gpuEnabled) "On — applies after app restart" else "Off — CPU inference, restart to apply",
                    onClick = {
                        val next = !gpuEnabled
                        gpuEnabled = next
                        scope.launch { com.motionsound.data.SoundPrefsStore.setGpuEnabled(context, next) }
                    }
                )
            }

            item {
                SettingsCard(
                    icon = ComicIcons.Delete,
                    title = "Stem Cache",
                    subtitle = "${cacheSizeMb} MB — tap to clear",
                    onClick = { showClearCacheDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Background & Battery",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                val bgGranted = checkBgLocationGranted(context)
                SettingsCard(
                    icon = ComicIcons.LocationOn,
                    title = "Background GPS",
                    subtitle = if (bgGranted) "Granted — works with screen off" else "Tap to grant 'Allow all the time'",
                    onClick = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    }
                )
            }

            item {
                val pm = context.getSystemService(PowerManager::class.java)
                val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                SettingsCard(
                    icon = ComicIcons.BatterySaver,
                    title = "Battery Optimization",
                    subtitle = if (exempt) "Exempt — app won't be killed" else "Tap to exempt — stops background kill",
                    onClick = {
                        if (!exempt) {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        }
                    }
                )
            }

            if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
                item {
                    SettingsCard(
                        icon = ComicIcons.PhoneAndroid,
                        title = "vivo Background Management",
                        subtitle = "Enable Autostart + Unrestricted background power",
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            }
                        }
                    )
                }
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
                    icon = ComicIcons.Info,
                    title = "App Info",
                    subtitle = "Version $versionName",
                    onClick = { showAppInfoDialog = true }
                )
            }

            item {
                SettingsCard(
                    icon = ComicIcons.Code,
                    title = "Developer Info",
                    subtitle = "MotionSound Dev",
                    onClick = { showDevInfoDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
            title = { Text("App Info") },
            text = {
                Column {
                    Text("MotionSound v$versionName")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A comic-styled music player with AI stem separation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppInfoDialog = false }) { Text("OK") }
            }
        )
    }

    if (showDevInfoDialog) {
        AlertDialog(
            onDismissRequest = { showDevInfoDialog = false },
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
            title = { Text("Developer Info") },
            text = {
                Column {
                    Text("MotionSound\nBuilt with Jetpack Compose & Material 3\nKotlin + LiteRT GPU (htdemucs)")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Developer: Nguyen Ngoc Thanh\nContact: ngocthanhgl@proton.me",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevInfoDialog = false }) { Text("OK") }
            }
        )
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
            title = { Text("AI Model") },
            text = {
                Column {
                    Text("htdemucs (on-device)")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Separates music into 4 stems: drums, bass, vocals, other.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val status = when {
                        driveState.modelLoaded -> "Loaded"
                        driveState.modelError != null -> driveState.modelError
                        driveState.downloadProgress > 0f && driveState.downloadProgress < 1f ->
                            "Downloading ${(driveState.downloadProgress * 100).toInt()}%"
                        else -> "Loading…"
                    }
                    Text(
                        text = "Status: $status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (driveState.inferenceBackend.isNotEmpty())
                            "Backend: ${driveState.inferenceBackend}" +
                                if (driveState.chunkMs > 0) " • ${driveState.chunkMs} ms/chunk" else ""
                        else "Backend: —",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("OK") }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
            title = { Text("Clear Stem Cache") },
            text = { Text("Delete ${cacheSizeMb} MB of cached stem files? They will be regenerated on next play.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) { stemCache.clearAll() }
                        cacheSizeMb = 0
                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            }
        )
    }
}

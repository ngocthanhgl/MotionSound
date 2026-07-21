package com.motionsound.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.app.Activity
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsound.drive.DriveUiState
import com.motionsound.drive.DriveViewModel
import com.motionsound.drive.VehiclePreset
import com.motionsound.model.Song
import com.motionsound.ui.components.DrivingStateIndicator
import com.motionsound.ui.components.EQVisualizer
import com.motionsound.ui.components.IntensityBar
import com.motionsound.ui.components.SliderSetting
import com.motionsound.ui.components.SpeedGauge
import com.motionsound.viewmodel.PlayerViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveScreen(
    playerViewModel: PlayerViewModel,
    driveViewModel: DriveViewModel = viewModel(),
    onMovingChanged: (Boolean) -> Unit = {}
) {
    val driveState by driveViewModel.driveState.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val song = playerState.currentSong
    var showSliders by remember { mutableStateOf(false) }
    var manualMoving by remember { mutableStateOf(false) }
    var wasMoving by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) { driveViewModel.startService() }

    val view = LocalView.current
    val window = remember(view.context) { (view.context as? Activity)?.window }

    val movingEnterKmh = 5f
    val movingExitKmh = 1f
    val isMoving = manualMoving ||
        if (wasMoving) driveState.speedKmh > movingExitKmh
        else driveState.speedKmh > movingEnterKmh
    if (wasMoving != isMoving) wasMoving = isMoving

    val toggleMoving = { manualMoving = !manualMoving }

    LaunchedEffect(isMoving) {
        onMovingChanged(isMoving)
    }

    LaunchedEffect(isMoving) {
        if (isMoving) {
            window?.insetsController?.hide(WindowInsets.Type.systemBars())
            window?.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window?.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    AnimatedContent(
        targetState = isMoving,
        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
        label = "drive_mode"
    ) { moving ->
        if (moving) {
            MovingLayout(
                driveState = driveState,
                song = song,
                onToggleMoving = toggleMoving
            )
        } else {
            IdleLayout(
                scrollState = scrollState,
                driveState = driveState,
                song = song,
                showSliders = showSliders,
                onToggleSliders = { showSliders = !showSliders },
                onToggleMoving = toggleMoving,
                driveViewModel = driveViewModel
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    driveState: DriveUiState,
    song: Song?,
    showSliders: Boolean,
    onToggleSliders: () -> Unit,
    onToggleMoving: () -> Unit,
    driveViewModel: DriveViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "Drive",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        SpeedGauge(
            speedKmh = driveState.speedKmh,
            maxSpeed = driveState.maxSpeedKmh,
            modifier = Modifier.padding(bottom = 8.dp),
            onClick = onToggleMoving
        )

        DrivingStateIndicator(
            state = driveState.drivingState,
            confidence = driveState.confidence,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                IntensityBar(
                    label = "ACCEL",
                    value = driveState.accelIntensity,
                    color = MaterialTheme.colorScheme.tertiary
                )
                IntensityBar(
                    label = "BRAKE",
                    value = driveState.brakeIntensity,
                    color = MaterialTheme.colorScheme.error
                )
                IntensityBar(
                    label = "CORNER",
                    value = driveState.cornerIntensity,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        EQVisualizer(bands = driveState.eqBandGains, modifier = Modifier.height(100.dp).padding(top = 4.dp))

        if (driveState.volumeReductionDb != 0f) {
            Text(
                text = "Volume ${driveState.volumeReductionDb.toInt()} dB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (song != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNullOrBlank().not()) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No song playing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "Vehicle",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = driveState.vehiclePreset == VehiclePreset.CAR,
                onClick = { driveViewModel.setVehiclePreset(VehiclePreset.CAR) },
                label = { Text("Car") },
                shape = RoundedCornerShape(16.dp)
            )
            FilterChip(
                selected = driveState.vehiclePreset == VehiclePreset.MOTORCYCLE,
                onClick = { driveViewModel.setVehiclePreset(VehiclePreset.MOTORCYCLE) },
                label = { Text("Motorcycle") },
                shape = RoundedCornerShape(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSliders() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "EQ Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (showSliders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (showSliders) "Collapse" else "Expand"
                    )
                }
                androidx.compose.animation.AnimatedVisibility(visible = showSliders) {
                    Column {
                        SliderSetting(
                            label = "Accel/Brake EQ Strength",
                            value = driveState.accelSensitivity,
                            onValueChange = driveViewModel::setAccelSensitivity,
                            valueRange = 0.1f..2f
                        )
                        SliderSetting(
                            label = "Corner EQ Strength",
                            value = driveState.cornerSensitivity,
                            onValueChange = driveViewModel::setCornerSensitivity,
                            valueRange = 0.1f..2f
                        )
                        SliderSetting(
                            label = "EQ Effect Depth",
                            value = driveState.effectDepth,
                            onValueChange = driveViewModel::setEffectDepth,
                            valueRange = 0f..1f
                        )
                        SliderSetting(
                            label = "Response Speed",
                            value = driveState.responseSpeed,
                            onValueChange = driveViewModel::setResponseSpeed,
                            valueRange = 0.1f..1f
                        )
                        SliderSetting(
                            label = "Sensor Sensitivity",
                            value = driveState.sensorSensitivity,
                            onValueChange = driveViewModel::setSensorSensitivity,
                            valueRange = 0.25f..4f,
                            valueLabel = "${"%.1f".format(driveState.sensorSensitivity)}x"
                        )
                        SliderSetting(
                            label = "Max Speed",
                            value = driveState.maxSpeedKmh.toFloat(),
                            onValueChange = { driveViewModel.setMaxSpeed(it.toInt()) },
                            valueRange = 20f..350f,
                            valueLabel = "${driveState.maxSpeedKmh} km/h"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovingLayout(
    driveState: DriveUiState,
    song: Song?,
    onToggleMoving: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        val gaugeHeight = (maxHeight * 0.50f).coerceIn(250.dp, 480.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpeedGauge(
                speedKmh = driveState.speedKmh,
                maxSpeed = driveState.maxSpeedKmh,
                modifier = Modifier.padding(horizontal = 16.dp),
                gaugeHeight = gaugeHeight,
                onClick = onToggleMoving,
                gaugeBackground = Color.Black,
                trackArcColor = Color.Black.copy(alpha = 0.15f)
            )

            Spacer(Modifier.height(24.dp))

            if (song != null) {
                val onSurface = MaterialTheme.colorScheme.onSurface
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNullOrBlank().not()) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "No song playing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(16.dp))

            EQVisualizer(
                bands = driveState.eqBandGains,
                modifier = Modifier.height(80.dp)
            )
        }
    }
}

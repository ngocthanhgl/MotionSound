package com.motionsound.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsound.drive.DriveViewModel
import com.motionsound.drive.VehiclePreset
import com.motionsound.ui.components.DrivingStateIndicator
import com.motionsound.ui.components.EQVisualizer
import com.motionsound.ui.components.IntensityBar
import com.motionsound.ui.components.SliderSetting
import com.motionsound.ui.components.SpeedGauge
import com.motionsound.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriveScreen(
    playerViewModel: PlayerViewModel,
    driveViewModel: DriveViewModel = viewModel()
) {
    val driveState by driveViewModel.driveState.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val song = playerState.currentSong
    var showSliders by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { driveViewModel.startService() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Drive") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        SpeedGauge(
            speedKmh = driveState.speedKmh,
            maxSpeed = driveState.maxSpeedKmh,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        DrivingStateIndicator(
            state = driveState.drivingState,
            confidence = driveState.confidence,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        IntensityBar(
            label = "ACCEL",
            value = driveState.accelIntensity,
            color = Color(0xFF4CAF50)
        )
        IntensityBar(
            label = "BRAKE",
            value = driveState.brakeIntensity,
            color = Color(0xFFF44336)
        )
        IntensityBar(
            label = "CORNER",
            value = driveState.cornerIntensity,
            color = Color(0xFFFF9800)
        )

        EQVisualizer(bands = driveState.eqBandGains, modifier = Modifier.height(40.dp).padding(top = 4.dp))

        if (driveState.volumeReductionDb != 0f || driveState.reverbIntensity > 0f) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (driveState.volumeReductionDb != 0f) {
                    Text(
                        text = "Volume ${driveState.volumeReductionDb.toInt()} dB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (driveState.reverbIntensity > 0f) {
                    Text(
                        text = "Reverb ${(driveState.reverbIntensity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (song != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNullOrBlank().not()) {
                    Text(
                        text = " · ${song.artist}",
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                label = { Text("Car") }
            )
            FilterChip(
                selected = driveState.vehiclePreset == VehiclePreset.MOTORCYCLE,
                onClick = { driveViewModel.setVehiclePreset(VehiclePreset.MOTORCYCLE) },
                label = { Text("Motorcycle") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { showSliders = !showSliders },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Icon(
                if (showSliders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(if (showSliders) "Hide EQ Settings" else "EQ Settings")
        }

        AnimatedVisibility(visible = showSliders) {
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
                    label = "Bump Filtering",
                    value = driveState.bumpFilterStrength,
                    onValueChange = driveViewModel::setBumpFilterStrength,
                    valueRange = 0.1f..2f
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

        Spacer(modifier = Modifier.height(16.dp))
    }
}

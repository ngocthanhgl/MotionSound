package com.motionsound.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.motionsound.drive.DriveViewModel
import com.motionsound.drive.VehiclePreset
import com.motionsound.ui.components.DrivingStateIndicator
import com.motionsound.ui.components.EQVisualizer
import com.motionsound.ui.components.IntensityBar
import com.motionsound.ui.components.PlayerControls
import com.motionsound.ui.components.SliderSetting
import com.motionsound.ui.components.SpeedGauge
import com.motionsound.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
            .statusBarsPadding()
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

        if (driveState.volumeReductionDb != 0f || driveState.reverbIntensity > 0f) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (driveState.volumeReductionDb != 0f) {
                    val text = if (driveState.volumeReductionDb > 0)
                        "Volume +${driveState.volumeReductionDb.toInt()} dB"
                    else
                        "Volume ${driveState.volumeReductionDb.toInt()} dB"
                    Text(
                        text = text,
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

        EQVisualizer(bands = driveState.eqBandGains, modifier = Modifier.height(60.dp).padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))

        if (song != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (song.albumArtUri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(song.albumArtUri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Album art",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
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
                    }

                    Slider(
                        value = if (playerState.durationMs > 0)
                            (playerState.currentPositionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
                        else 0f,
                        onValueChange = {},
                        onValueChangeFinished = {},
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    PlayerControls(
                        isPlaying = playerState.isPlaying,
                        onPlayPause = playerViewModel::togglePlayPause,
                        onPrevious = playerViewModel::playPrevious,
                        onNext = playerViewModel::playNext,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select a song to start",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { showSliders = !showSliders },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Icon(
                if (showSliders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Vehicle Preset",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

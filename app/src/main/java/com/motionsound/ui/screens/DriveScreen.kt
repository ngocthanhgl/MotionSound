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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import com.motionsound.drive.DriveViewModel
import com.motionsound.model.Song
import com.motionsound.ui.components.DrivingStateIndicator
import com.motionsound.ui.components.IntensityBar
import com.motionsound.ui.components.SpeedGauge
import com.motionsound.ui.components.StemVolumeSlider
import com.motionsound.viewmodel.PlayerViewModel

@Composable
fun DriveScreen(
    playerViewModel: PlayerViewModel,
    driveViewModel: DriveViewModel = viewModel(),
    onMovingChanged: (Boolean) -> Unit = {}
) {
    val driveState by driveViewModel.driveState.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val song = playerState.currentSong
    var manualMoving by remember { mutableStateOf(false) }
    var wasMoving by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

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
        kotlinx.coroutines.delay(100)
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
                onToggleMoving = toggleMoving,
                driveViewModel = driveViewModel
            )
        }
    }
}

@Composable
private fun IdleLayout(
    scrollState: androidx.compose.foundation.ScrollState,
    driveState: com.motionsound.stem.StemUiState,
    song: Song?,
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
            confidence = 1f,
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

        if (driveState.separationProgress > 0f && driveState.separationProgress < 1f) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Separating stems…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { driveState.separationProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        } else if (driveState.downloadProgress > 0f && driveState.downloadProgress < 1f) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Downloading AI model (${(driveState.downloadProgress * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { driveState.downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        } else if (!driveState.modelLoaded) {
            Text(
                text = driveState.modelError ?: "AI model not loaded — stem separation unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "Stem Mix",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                StemVolumeSlider(
                    label = "Drums",
                    value = driveState.volumeDrums,
                    onValueChange = driveViewModel::setVolumeDrums,
                    color = MaterialTheme.colorScheme.tertiary
                )
                StemVolumeSlider(
                    label = "Bass",
                    value = driveState.volumeBass,
                    onValueChange = driveViewModel::setVolumeBass,
                    color = MaterialTheme.colorScheme.primary
                )
                StemVolumeSlider(
                    label = "Other",
                    value = driveState.volumeOther,
                    onValueChange = driveViewModel::setVolumeOther,
                    color = MaterialTheme.colorScheme.secondary
                )
                StemVolumeSlider(
                    label = "Vocals",
                    value = driveState.volumeVocals,
                    onValueChange = driveViewModel::setVolumeVocals,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
            Text(
                text = "No song playing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MovingLayout(
    driveState: com.motionsound.stem.StemUiState,
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
        }
    }
}

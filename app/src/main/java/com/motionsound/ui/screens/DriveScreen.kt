package com.motionsound.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.app.Activity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsound.drive.DriveViewModel
import com.motionsound.model.Song
import com.motionsound.sounddrive.GestureType
import com.motionsound.sounddrive.SoundDriveMode
import com.motionsound.ui.components.AmbientMoodBadge
import com.motionsound.ui.components.DrivingStateIndicator
import com.motionsound.ui.components.GestureIndicator
import com.motionsound.ui.components.HillGradeIndicator
import com.motionsound.ui.components.RoadRoughnessBar
import com.motionsound.ui.components.SpeedGauge
import com.motionsound.ui.theme.ComicIcons
import com.motionsound.ui.theme.ComicProgressBar
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder
import com.motionsound.ui.theme.comicPanel
import com.motionsound.viewmodel.PlayerUiState
import com.motionsound.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val view = LocalView.current
    val window = remember(view.context) { (view.context as? Activity)?.window }

    val movingEnterKmh = 5f
    val movingExitKmh = 1f
    val isMoving = manualMoving ||
        if (wasMoving) driveState.speedKmh > movingExitKmh
        else driveState.speedKmh > movingEnterKmh
    if (wasMoving != isMoving) wasMoving = isMoving

    val toggleMoving = { manualMoving = !manualMoving }

    BackHandler(enabled = isMoving && manualMoving) {
        manualMoving = false
    }

    LaunchedEffect(isMoving) {
        onMovingChanged(isMoving)
    }

    LaunchedEffect(isMoving) {
        if (isMoving) {
            window?.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window?.insetsController?.hide(WindowInsets.Type.systemBars())
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.insetsController?.show(WindowInsets.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.insetsController?.show(WindowInsets.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = isMoving,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "drive_mode"
        ) { moving ->
        if (moving) {
            MovingLayout(
                driveState = driveState,
                song = song,
                playerState = playerState,
                onToggleMoving = toggleMoving,
                onExit = {
                    if (manualMoving) manualMoving = false
                    else scope.launch {
                        snackbarHostState.showSnackbar("You're still moving — slow down below 1 km/h to exit")
                    }
                },
                onPlayPause = { playerViewModel.togglePlayPause() },
                onNext = { playerViewModel.playNext() },
                onPrevious = { playerViewModel.playPrevious() }
            )
        } else {
            IdleLayout(
                driveState = driveState,
                song = song,
                playerState = playerState,
                onToggleMoving = toggleMoving,
                driveViewModel = driveViewModel,
                onPlayPause = { playerViewModel.togglePlayPause() },
                onNext = { playerViewModel.playNext() },
                onPrevious = { playerViewModel.playPrevious() }
            )
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun IdleLayout(
    driveState: com.motionsound.stem.StemUiState,
    song: Song?,
    playerState: PlayerUiState,
    onToggleMoving: () -> Unit,
    driveViewModel: DriveViewModel,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val comic = LocalComicColors.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gaugeHeight = (maxHeight * 0.28f).coerceIn(170.dp, 240.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Drive",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SpeedGauge(
                        speedKmh = driveState.speedKmh,
                        maxSpeed = driveState.maxSpeedKmh,
                        gaugeHeight = gaugeHeight,
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = onToggleMoving
                    )

                    GestureIndicator(
                        gesture = driveState.gestureIndicator,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (driveState.hasSensorData) {
                            AmbientMoodBadge(ambientMood = driveState.ambientMood)
                        }
                        HillGradeIndicator(hillGrade = driveState.hillGrade)
                    }

                    DrivingStateIndicator(
                        state = driveState.drivingState
                    )

                    if (driveState.hasSensorData) {
                        RoadRoughnessBar(roadRoughness = driveState.roadRoughness)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    val separating = driveState.separationProgress > 0f && driveState.separationProgress < 1f
                    val downloading = driveState.downloadProgress > 0f && driveState.downloadProgress < 1f
                    AnimatedVisibility(
                        visible = separating,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "Separating stems…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ComicProgressBar(
                                progress = driveState.separationProgress,
                                color = MaterialTheme.colorScheme.primary,
                                borderColor = comic.ink,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = downloading,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "Downloading AI model (${(driveState.downloadProgress * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ComicProgressBar(
                                progress = driveState.downloadProgress,
                                color = MaterialTheme.colorScheme.primary,
                                borderColor = comic.ink,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = !driveState.modelLoaded && !separating && !downloading,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        Text(
                            text = driveState.modelError ?: "Loading AI model…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (driveState.modelError != null) MaterialTheme.colorScheme.error
                            else comic.textMuted,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                SoundDrivePanel(
                    driveState = driveState,
                    driveViewModel = driveViewModel
                )

                DrivePlaybackControls(
                    isPlaying = playerState.isPlaying,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    playSize = 64.dp,
                    sideSize = 56.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (song != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MovingLayout(
    driveState: com.motionsound.stem.StemUiState,
    song: Song?,
    playerState: PlayerUiState,
    onToggleMoving: () -> Unit,
    onExit: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val comic = LocalComicColors.current
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White)
                        .comicBorder(Color.Black, 2.5.dp, cornerRadius = 0.dp)
                        .clickable(onClick = onExit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ComicIcons.Clear,
                        contentDescription = "Exit moving mode",
                        tint = Color.Black
                    )
                }
            }

            SpeedGauge(
                speedKmh = driveState.speedKmh,
                maxSpeed = driveState.maxSpeedKmh,
                modifier = Modifier.padding(horizontal = 16.dp),
                gaugeHeight = gaugeHeight,
                onClick = onToggleMoving,
                gaugeBackground = Color.Black,
                trackArcColor = Color.Black.copy(alpha = 0.15f)
            )

            if (driveState.soundDriveEnabled) {
                Text(
                    text = "SOUND DRIVE · ${driveState.soundDriveMode.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = comic.yellow
                )
            } else {
                Text(
                    text = "SOUND DRIVE OFF",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            GestureIndicator(
                gesture = driveState.gestureIndicator,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            if (song != null) {
                val onSurface = Color.White
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
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(16.dp))

            DrivePlaybackControls(
                isPlaying = playerState.isPlaying,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                playSize = 80.dp,
                sideSize = 64.dp
            )
        }
    }
}

@Composable
private fun DrivePlaybackControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    playSize: Dp,
    sideSize: Dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DriveControlButton(
            icon = ComicIcons.SkipPrevious,
            contentDescription = "Previous",
            onClick = onPrevious,
            size = sideSize
        )
        DriveControlButton(
            icon = if (isPlaying) ComicIcons.Pause else ComicIcons.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            size = playSize
        )
        DriveControlButton(
            icon = ComicIcons.SkipNext,
            contentDescription = "Next",
            onClick = onNext,
            size = sideSize
        )
    }
}

@Composable
private fun DriveControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp
) {
    val comic = LocalComicColors.current
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(size)
            .background(comic.yellow)
            .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
            .clickable(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = comic.ink,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

@Composable
private fun SoundDrivePanel(
    driveState: com.motionsound.stem.StemUiState,
    driveViewModel: DriveViewModel
) {
    val comic = LocalComicColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .comicPanel(
                containerColor = comic.surface,
                borderColor = comic.ink,
                shadowColor = comic.shadow,
                borderWidth = 2.5.dp,
                shadowOffset = 4.dp,
                cornerRadius = 0.dp
            )
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sound Drive",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = driveState.soundDriveEnabled,
                    enabled = driveState.modelLoaded,
                    onCheckedChange = { driveViewModel.toggleSoundDrive() }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedVisibility(
                    visible = driveState.soundDriveEnabled,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            SoundDriveMode.BALANCED to "Comfort",
                            SoundDriveMode.DYNAMIC to "Sport",
                            SoundDriveMode.IMMERSIVE to "Immersive"
                        ).forEach { (mode, label) ->
                            val selected = driveState.soundDriveMode == mode
                            val bg = if (selected) comic.yellow else comic.surfaceAlt
                            val fg = if (selected) comic.ink else comic.textMuted
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(bg)
                                    .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
                                    .alpha(if (driveState.modelLoaded) 1f else 0.4f)
                                    .clickable(enabled = driveState.modelLoaded) {
                                        driveViewModel.setSoundDriveMode(mode)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = fg,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

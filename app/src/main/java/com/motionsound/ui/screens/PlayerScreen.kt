package com.motionsound.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.motionsound.ui.components.DotSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.motionsound.drive.DriveViewModel
import com.motionsound.ui.components.PlayerControls
import com.motionsound.ui.components.formatDuration
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.ComicProgressBar
import com.motionsound.ui.theme.comicPanel
import com.motionsound.viewmodel.PlayerViewModel

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    driveViewModel: DriveViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val driveState by driveViewModel.driveState.collectAsState()
    val song = uiState.currentSong

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (song == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ComicIcons.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Select a song to start playing",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                AnimatedContent(
                    targetState = song.albumArtUri to song.title,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(300)) using
                            SizeTransform(clip = false)
                    },
                    label = "album_art"
                ) { (artUri, _) ->
                    val comic = LocalComicColors.current
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .aspectRatio(1f)
                            .comicPanel(
                                containerColor = comic.surfaceAlt,
                                borderColor = comic.ink,
                                shadowColor = comic.shadow,
                                borderWidth = 3.dp,
                                shadowOffset = 6.dp,
                                cornerRadius = 0.dp
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.separatingUri == song.uri) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "SEPARATING STEMS…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = comic.ink
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                ComicProgressBar(
                                    progress = uiState.separationProgress.coerceAtLeast(0.05f),
                                    color = comic.yellow,
                                    borderColor = comic.ink,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Tách drums / bass / vocals lần đầu chơi, mất khoảng 1 phút",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = comic.textMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else if (artUri != null) {
                            AsyncImage(
                                model = artUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(0.dp))
                            )
                        } else {
                            Icon(
                                imageVector = ComicIcons.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = comic.textMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(uiState.currentPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDuration(uiState.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                var sliderPosition by rememberSaveable { mutableStateOf(0f) }
                var isDragging by rememberSaveable { mutableStateOf(false) }
                var lastSeekedPosition by rememberSaveable { mutableStateOf(-1L) }

                val displayPosition = when {
                    isDragging -> sliderPosition
                    lastSeekedPosition > 0 -> lastSeekedPosition.toFloat()
                    else -> uiState.currentPositionMs.toFloat()
                }

                DotSlider(
                    value = displayPosition,
                    onValueChange = {
                        sliderPosition = it
                        isDragging = true
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(sliderPosition.toLong())
                        lastSeekedPosition = sliderPosition.toLong()
                        isDragging = false
                    },
                    valueRange = 0f..uiState.durationMs.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                PlayerControls(
                    isPlaying = uiState.isPlaying,
                    onPlayPause = viewModel::togglePlayPause,
                    onPrevious = viewModel::playPrevious,
                    onNext = viewModel::playNext,
                    isShuffled = uiState.isShuffled,
                    onShuffleToggle = viewModel::toggleShuffle,
                    isLoop = uiState.isLoopEnabled,
                    onLoopToggle = viewModel::toggleLoop,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

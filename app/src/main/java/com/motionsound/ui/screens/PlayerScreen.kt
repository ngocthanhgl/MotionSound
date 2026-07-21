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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import com.motionsound.ui.components.DotSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.motionsound.ui.components.PlayerControls
import com.motionsound.viewmodel.PlayerViewModel

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val song = uiState.currentSong

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (song == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
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
                    Card(
                        modifier = Modifier.size(280.dp).aspectRatio(1f),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (artUri != null) {
                                AsyncImage(
                                    model = artUri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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

                LaunchedEffect(lastSeekedPosition, uiState.currentPositionMs) {
                    if (lastSeekedPosition > 0 &&
                        uiState.currentPositionMs >= lastSeekedPosition - 200
                    ) {
                        lastSeekedPosition = -1L
                    }
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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

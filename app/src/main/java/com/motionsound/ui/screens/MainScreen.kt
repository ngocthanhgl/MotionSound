package com.motionsound.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.QueueMusic
import androidx.compose.material.icons.sharp.MusicNote
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.motionsound.drive.DriveViewModel
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder
import com.motionsound.ui.theme.comicPanel
import com.motionsound.viewmodel.PlayerViewModel

@Composable
fun MainScreen() {
    val playerViewModel: PlayerViewModel = viewModel()
    val driveViewModel: DriveViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }
    val playerState by playerViewModel.uiState.collectAsState()
    var driveMoving by remember { mutableStateOf(false) }
    val comic = LocalComicColors.current

    Scaffold(
        bottomBar = {
            Column {
                if (playerState.hasStartedPlayback && (selectedTab != 0 || !driveMoving)) {
                    val song = playerState.currentSong
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .comicPanel(
                                containerColor = comic.surface,
                                borderColor = comic.ink,
                                shadowColor = comic.shadow,
                                borderWidth = 2.5.dp,
                                shadowOffset = 4.dp,
                                cornerRadius = 0.dp
                            )
                            .clickable { selectedTab = 1 }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (song?.albumArtUri != null) {
                                AsyncImage(
                                    model = song.albumArtUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(0.dp))
                                        .background(comic.surfaceAlt)
                                        .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(0.dp))
                                        .background(comic.surfaceAlt)
                                        .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Sharp.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = comic.textMuted
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song?.title ?: "Unknown",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song?.artist ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = comic.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = playerViewModel::togglePlayPause) {
                                Icon(
                                    if (playerState.isPlaying) Icons.Sharp.Pause
                                    else Icons.Sharp.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (selectedTab != 0 || !driveMoving) {
                    Box {
                        NavigationBar(containerColor = comic.surface) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Sharp.Speed, contentDescription = null) },
                                label = { Text("Drive") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = comic.yellow,
                                    selectedIconColor = comic.ink,
                                    selectedTextColor = comic.ink,
                                    unselectedIconColor = comic.textMuted,
                                    unselectedTextColor = comic.textMuted
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Sharp.MusicNote, contentDescription = null) },
                                label = { Text("Player") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = comic.yellow,
                                    selectedIconColor = comic.ink,
                                    selectedTextColor = comic.ink,
                                    unselectedIconColor = comic.textMuted,
                                    unselectedTextColor = comic.textMuted
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                                label = { Text("Songs") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = comic.yellow,
                                    selectedIconColor = comic.ink,
                                    selectedTextColor = comic.ink,
                                    unselectedIconColor = comic.textMuted,
                                    unselectedTextColor = comic.textMuted
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Sharp.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = comic.yellow,
                                    selectedIconColor = comic.ink,
                                    selectedTextColor = comic.ink,
                                    unselectedIconColor = comic.textMuted,
                                    unselectedTextColor = comic.textMuted
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(comic.ink)
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(300)
            ) { tab ->
                when (tab) {
                    0 -> DriveScreen(
                        playerViewModel = playerViewModel,
                        driveViewModel = driveViewModel,
                        onMovingChanged = { driveMoving = it }
                    )
                    1 -> PlayerScreen(viewModel = playerViewModel, driveViewModel = driveViewModel)
                    2 -> SongListScreen(
                        viewModel = playerViewModel,
                        onSongClick = { selectedTab = 1 }
                    )
                    3 -> SettingsScreen()
                }
            }
        }
    }
}
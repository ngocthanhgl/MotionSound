package com.motionsound.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.motionsound.data.SoundPrefsStore
import com.motionsound.drive.DriveViewModel
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.ComicProgressBar
import com.motionsound.ui.theme.comicBorder
import com.motionsound.ui.theme.comicPanel
import com.motionsound.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val playerViewModel: PlayerViewModel = viewModel()
    val driveViewModel: DriveViewModel = viewModel()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val playerState by playerViewModel.uiState.collectAsState()
    var driveMoving by remember { mutableStateOf(false) }
    var showPlayerSheet by rememberSaveable { mutableStateOf(false) }
    val songsListState = rememberLazyListState()
    val playlistsListState = rememberLazyListState()
    val playlistDetailListState = rememberLazyListState()
    val comic = LocalComicColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val darkMode by remember { SoundPrefsStore.darkFlow(context.applicationContext) }
        .collectAsState(initial = false)

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
                            .clickable { showPlayerSheet = true }
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
                                        ComicIcons.MusicNote,
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
                                    overflow = TextOverflow.Visible,
                                    modifier = Modifier.basicMarquee()
                                )
                                Text(
                                    text = song?.artist ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = comic.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (song != null && song.uri == playerState.separatingUri) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ComicProgressBar(
                                        progress = playerState.separationProgress.coerceAtLeast(0.05f),
                                        color = comic.yellow,
                                        borderColor = comic.ink,
                                        modifier = Modifier.fillMaxWidth(),
                                        height = 6.dp
                                    )
                                }
                            }
                            IconButton(onClick = playerViewModel::togglePlayPause) {
                                Icon(
                                    if (playerState.isPlaying) ComicIcons.Pause
                                    else ComicIcons.PlayArrow,
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
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = 0
                                },
                                icon = { Icon(ComicIcons.Speed, contentDescription = null) },
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
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = 1
                                },
                                icon = { Icon(ComicIcons.QueueMusic, contentDescription = null) },
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
                                selected = selectedTab == 2,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = 2
                                },
                                icon = { Icon(ComicIcons.Settings, contentDescription = null) },
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
                    1 -> SongListScreen(
                        viewModel = playerViewModel,
                        onSongClick = { showPlayerSheet = true },
                        songsListState = songsListState,
                        playlistsListState = playlistsListState,
                        playlistDetailListState = playlistDetailListState
                    )
                    2 -> SettingsScreen(
                        darkMode = darkMode,
                        onDarkModeChange = { dark ->
                            scope.launch { SoundPrefsStore.setDark(context.applicationContext, dark) }
                        }
                    )
                }
            }
            if (showPlayerSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showPlayerSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = comic.surface,
                    scrimColor = comic.ink.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(vertical = 10.dp)
                                .width(40.dp)
                                .height(4.dp)
                                .background(comic.ink)
                        )
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(comic.ink)
                        )
                        PlayerScreen(
                            viewModel = playerViewModel,
                            driveViewModel = driveViewModel,
                            onClose = { showPlayerSheet = false }
                        )
                    }
                }
            }
        }
    }
}
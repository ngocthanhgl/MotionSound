package com.motionsound.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsound.model.Playlist
import com.motionsound.model.Song
import com.motionsound.ui.components.AddToPlaylistDialog
import com.motionsound.ui.components.PlaylistCard
import com.motionsound.ui.components.SongItem
import com.motionsound.viewmodel.PlayerUiState
import com.motionsound.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    viewModel: PlayerViewModel = viewModel(),
    onSongClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var dialogSongId by remember { mutableStateOf<Long?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val selectedPlaylist = uiState.playlists.find { it.id == uiState.selectedPlaylistId }
    val playlistSongs = uiState.playlistSongs(uiState.songs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(selectedPlaylist?.name ?: "Songs")
            },
            navigationIcon = {
                if (selectedPlaylist != null) {
                    IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            },
            actions = {
                if (selectedPlaylist != null) {
                    IconButton(onClick = {
                        if (playlistSongs.isNotEmpty()) {
                            viewModel.playShuffled(playlistSongs)
                            onSongClick()
                        }
                    }) {
                        Icon(Icons.Filled.Shuffle, "Shuffle")
                    }
                } else {
                    IconButton(onClick = { viewModel.refreshSongs() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (selectedPlaylist != null) {
            PlaylistDetailContent(
                songs = playlistSongs,
                onPlay = {
                    viewModel.playShuffled(playlistSongs)
                    onSongClick()
                },
                onRemove = { songId ->
                    viewModel.removeSongFromPlaylist(selectedPlaylist.id, songId)
                }
            )
        } else {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Songs") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Playlists") }
                )
            }

            when (selectedTab) {
                0 -> SongsTabContent(
                    songs = uiState.songs,
                    onSongClick = { index ->
                        viewModel.playSong(index)
                        onSongClick()
                    },
                    onAddToPlaylist = { songId ->
                        dialogSongId = songId
                        showAddToPlaylistDialog = true
                    }
                )
                1 -> PlaylistsTabContent(
                    playlists = uiState.playlists,
                    songs = uiState.songs,
                    onPlaylistClick = { plId -> viewModel.selectPlaylist(plId) },
                    onDeletePlaylist = { plId -> viewModel.deletePlaylist(plId) },
                    onCreatePlaylist = { showCreatePlaylist = true }
                )
            }
        }
    }

    if (showAddToPlaylistDialog && dialogSongId != null) {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            currentSongId = dialogSongId!!,
            onAddToPlaylist = { playlistId ->
                viewModel.addSongToPlaylist(playlistId, dialogSongId!!)
                showAddToPlaylistDialog = false
            },
            onCreateNew = {
                showAddToPlaylistDialog = false
                showCreatePlaylist = true
            },
            onDismiss = { showAddToPlaylistDialog = false }
        )
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylist = false
                newPlaylistName = ""
            },
            title = { Text("New playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylist = false
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylist = false
                    newPlaylistName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SongsTabContent(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onAddToPlaylist: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        itemsIndexed(songs) { index, song ->
            SongItem(
                song = song,
                onClick = { onSongClick(index) },
                trailing = {
                    IconButton(onClick = { onAddToPlaylist(song.id) }) {
                        Icon(
                            Icons.Filled.PlaylistAdd,
                            contentDescription = "Add to playlist",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    songs: List<Song>,
    onPlaylistClick: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        itemsIndexed(playlists) { _, pl ->
            PlaylistCard(
                playlist = pl,
                songCount = songs.count { it.id in pl.songIds },
                onClick = { onPlaylistClick(pl.id) },
                onDelete = { onDeletePlaylist(pl.id) }
            )
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreatePlaylist() }
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.padding(start = 12.dp))
                Text(
                    "Create playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    songs: List<Song>,
    onPlay: () -> Unit,
    onRemove: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        itemsIndexed(songs) { _, song ->
            SongItem(
                song = song,
                onClick = onPlay,
                trailing = {
                    IconButton(onClick = { onRemove(song.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove from playlist",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    text = "No songs in this playlist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

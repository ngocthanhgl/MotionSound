package com.motionsound.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionsound.model.Playlist
import com.motionsound.model.Song
import com.motionsound.ui.components.AddToPlaylistDialog
import com.motionsound.ui.components.PlaylistCard
import com.motionsound.ui.components.SongItem
import com.motionsound.ui.components.StemsStatus
import com.motionsound.ui.theme.ComicProgressBar
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicPanel
import com.motionsound.viewmodel.PlayerUiState
import com.motionsound.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

private enum class SongSort(val label: String) {
    TITLE_AZ("Title A-Z"),
    ARTIST_AZ("Artist A-Z"),
    DURATION_SHORT("Shortest first"),
    DURATION_LONG("Longest first"),
    RECENT("Recently added")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    viewModel: PlayerViewModel = viewModel(),
    onSongClick: () -> Unit,
    songsListState: LazyListState,
    playlistsListState: LazyListState,
    playlistDetailListState: LazyListState
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var dialogSongId by remember { mutableStateOf<Long?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }

    val selectedPlaylist = uiState.playlists.find { it.id == uiState.selectedPlaylistId }
    val playlistSongs = uiState.playlistSongs(uiState.songs)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(SongSort.TITLE_AZ) }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortedSongs = when (sortMode) {
        SongSort.TITLE_AZ -> uiState.songs.sortedBy { it.title.lowercase() }
        SongSort.ARTIST_AZ -> uiState.songs.sortedBy { it.artist.lowercase() }
        SongSort.DURATION_SHORT -> uiState.songs.sortedBy { it.durationMs }
        SongSort.DURATION_LONG -> uiState.songs.sortedByDescending { it.durationMs }
        SongSort.RECENT -> uiState.songs.sortedByDescending { it.dateAdded }
    }
    val filteredSongs = if (searchQuery.isBlank()) sortedSongs
    else sortedSongs.filter { it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true) }
    val filteredIndices = filteredSongs.map { s -> uiState.songs.indexOf(s) }.filter { it >= 0 }

    val stemsStatusOf: (Song) -> StemsStatus? = { song ->
        when {
            uiState.separatingUri == song.uri -> StemsStatus.SEPARATING
            song.uri in uiState.stemsReadyUris -> StemsStatus.READY
            else -> null
        }
    }

    val prevReadyUris = remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(uiState.stemsReadyUris) {
        val now = uiState.stemsReadyUris
        val prev = prevReadyUris.value
        prevReadyUris.value = now
        if (prev != null) {
            val fresh = now - prev
            if (fresh.isNotEmpty()) {
                val s = uiState.songs.firstOrNull { it.uri in fresh }
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (s != null) "Stems ready: ${s.title}" else "Stems ready"
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedPlaylist != null) {
                IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                    Icon(ComicIcons.ArrowBack, "Back")
                }
                Text(
                    text = selectedPlaylist.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = playlistSongs.isNotEmpty(),
                    onClick = {
                        viewModel.playShuffled(playlistSongs)
                        onSongClick()
                    }
                ) {
                    Icon(ComicIcons.Shuffle, "Shuffle")
                }
                IconButton(onClick = { viewModel.preProcessPlaylist() }) {
                    if (uiState.preCacheProgress.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(ComicIcons.Download, "Separate stems")
                    }
                }
            } else {
                Text(
                    text = "Songs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { showSortMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sort",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SongSort.values().forEach { s ->
                            DropdownMenuItem(
                                leadingIcon = if (sortMode == s) {
                                    { Icon(ComicIcons.Check, contentDescription = null) }
                                } else null,
                                text = {
                                    Text(
                                        text = s.label,
                                        fontWeight = if (sortMode == s) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    sortMode = s
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(
                        if (showSearch) ComicIcons.Clear else ComicIcons.Search,
                        if (showSearch) "Close search" else "Search"
                    )
                }
                IconButton(onClick = { viewModel.preProcessMissingSongs() }) {
                    if (uiState.preCacheProgress.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(ComicIcons.Download, "Separate all")
                    }
                }
                IconButton(onClick = { viewModel.refreshSongs() }) {
                    Icon(ComicIcons.Refresh, "Refresh")
                }
            }
        }

        val pre = uiState.preCacheProgress
        if (pre.isRunning && pre.total > 0) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            append("Separating stems ${pre.completed}/${pre.total}")
                            if (pre.failed > 0) append("  · ${pre.failed} failed")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pre.failed > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${((pre.completed + pre.fraction) * 100f / pre.total).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { viewModel.cancelPreCache() }) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                ComicProgressBar(
                    progress = (pre.completed + pre.fraction).toFloat() / pre.total,
                    color = MaterialTheme.colorScheme.primary,
                    borderColor = LocalComicColors.current.ink,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showSearch && selectedPlaylist == null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs...") },
                leadingIcon = { Icon(ComicIcons.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(ComicIcons.Clear, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (selectedPlaylist != null) {
            PlaylistDetailContent(
                songs = playlistSongs,
                onPlay = { index ->
                    viewModel.playSong(index)
                    onSongClick()
                },
                onRemove = { songId ->
                    viewModel.removeSongFromPlaylist(selectedPlaylist.id, songId)
                    scope.launch { snackbarHostState.showSnackbar("Removed from playlist") }
                },
                stemsStatusOf = stemsStatusOf,
                currentUri = uiState.currentSong?.uri,
                listState = playlistDetailListState
            )
        } else {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = LocalComicColors.current.surface,
                divider = {}
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Songs") },
                    selectedContentColor = LocalComicColors.current.ink,
                    unselectedContentColor = LocalComicColors.current.textMuted
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Playlists") },
                    selectedContentColor = LocalComicColors.current.ink,
                    unselectedContentColor = LocalComicColors.current.textMuted
                )
            }

            when (selectedTab) {
                0 -> {
                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            ComicProgressBar(
                                progress = 0f,
                                indeterminate = true,
                                color = LocalComicColors.current.yellow,
                                borderColor = LocalComicColors.current.ink,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                                height = 10.dp
                            )
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = uiState.isLoading,
                            onRefresh = { viewModel.refreshSongs() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            SongsTabContent(
                                songs = filteredSongs,
                                onSongClick = { index ->
                                    val originalIndex = filteredIndices.getOrElse(index) { index }
                                    viewModel.playSong(originalIndex.coerceIn(0, uiState.songs.lastIndex))
                                    onSongClick()
                                },
                                onAddToPlaylist = { songId ->
                                    dialogSongId = songId
                                    showAddToPlaylistDialog = true
                                },
                                onSeparate = { songId -> viewModel.preProcessSong(songId) },
                                onPlayNext = { song ->
                                    viewModel.playNextSong(song)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Will play next: ${song.title}")
                                    }
                                },
                                onAddToQueue = { song ->
                                    viewModel.addToQueue(song)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Added to queue: ${song.title}")
                                    }
                                },
                                stemsStatusOf = stemsStatusOf,
                                currentUri = uiState.currentSong?.uri,
                                searchActive = showSearch && searchQuery.isNotBlank(),
                                query = searchQuery,
                                listState = songsListState
                            )
                        }
                    }
                }
                1 -> PlaylistsTabContent(
                    playlists = uiState.playlists,
                    songs = uiState.songs,
                    onPlaylistClick = { plId -> viewModel.selectPlaylist(plId) },
                    onDeletePlaylist = { plId ->
                        playlistToDelete = uiState.playlists.find { it.id == plId }
                    },
                    onCreatePlaylist = { showCreatePlaylist = true },
                    listState = playlistsListState
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
                scope.launch { snackbarHostState.showSnackbar("Added to playlist") }
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
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
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
                            val newId = viewModel.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylist = false
                            if (newId != null && dialogSongId != null) {
                                viewModel.addSongToPlaylist(newId, dialogSongId!!)
                                scope.launch { snackbarHostState.showSnackbar("Playlist created, song added") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Playlist created") }
                            }
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

    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            shape = RoundedCornerShape(0.dp),
            containerColor = LocalComicColors.current.surface,
            title = { Text("Delete playlist?") },
            text = { Text("\"${pl.name}\" will be deleted. Songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(pl.id)
                    playlistToDelete = null
                    scope.launch { snackbarHostState.showSnackbar("Playlist deleted") }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

@Composable
private fun SongsTabContent(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onSeparate: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    stemsStatusOf: (Song) -> StemsStatus?,
    currentUri: String? = null,
    searchActive: Boolean = false,
    query: String = "",
    listState: LazyListState
) {
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current
    val audioGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (songs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ComicIcons.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchActive) "No results for \"$query\""
                    else if (!audioGranted) "Allow music access to see your songs"
                    else "No songs found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!audioGranted && !searchActive) {
                    Spacer(modifier = Modifier.height(20.dp))
                    val comic = LocalComicColors.current
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(46.dp)
                            .comicPanel(
                                containerColor = comic.yellow,
                                borderColor = comic.ink,
                                shadowColor = comic.shadow,
                                borderWidth = 2.5.dp,
                                shadowOffset = 4.dp,
                                cornerRadius = 0.dp
                            )
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Open Settings",
                            style = MaterialTheme.typography.titleSmall,
                            color = comic.ink
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                Box {
                    SongItem(
                        song = song,
                        onClick = { onSongClick(index) },
                        onLongClick = { menuSong = song },
                        stemsStatus = stemsStatusOf(song),
                        isCurrent = currentUri == song.uri,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (stemsStatusOf(song) != StemsStatus.READY) {
                                    IconButton(onClick = { onSeparate(song.id) }) {
                                        Icon(
                                            ComicIcons.Download,
                                            contentDescription = "Separate stems",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { onAddToPlaylist(song.id) }) {
                                    Icon(
                                        ComicIcons.PlaylistAdd,
                                        contentDescription = "Add to playlist",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = menuSong?.id == song.id,
                        onDismissRequest = { menuSong = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            onClick = {
                                menuSong = null
                                onPlayNext(song)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to queue") },
                            onClick = {
                                menuSong = null
                                onAddToQueue(song)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            onClick = {
                                menuSong = null
                                onAddToPlaylist(song.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Separate stems") },
                            onClick = {
                                menuSong = null
                                onSeparate(song.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    songs: List<Song>,
    onPlaylistClick: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    listState: LazyListState
) {
    if (playlists.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ComicIcons.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No playlists yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .clickable { onCreatePlaylist() }
                        .comicPanel(
                            containerColor = MaterialTheme.colorScheme.primary,
                            borderColor = LocalComicColors.current.ink,
                            shadowColor = LocalComicColors.current.shadow,
                            borderWidth = 3.dp,
                            shadowOffset = 4.dp,
                            cornerRadius = 0.dp
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Create playlist",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(playlists, key = { _, p -> p.id }) { _, pl ->
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
                    ComicIcons.Add,
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
    onPlay: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    stemsStatusOf: (Song) -> StemsStatus? = { null },
    currentUri: String? = null,
    listState: LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongItem(
                song = song,
                onClick = { onPlay(index) },
                stemsStatus = stemsStatusOf(song),
                isCurrent = currentUri == song.uri,
                trailing = {
                    IconButton(onClick = { onRemove(song.id) }) {
                        Icon(
                            ComicIcons.Delete,
                            contentDescription = "Remove from playlist",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
        if (songs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 48.dp)
                        .comicPanel(
                            containerColor = LocalComicColors.current.surface,
                            borderColor = LocalComicColors.current.ink,
                            shadowColor = LocalComicColors.current.shadow,
                            borderWidth = 2.5.dp,
                            shadowOffset = 4.dp,
                            cornerRadius = 0.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = ComicIcons.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No songs in this playlist yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

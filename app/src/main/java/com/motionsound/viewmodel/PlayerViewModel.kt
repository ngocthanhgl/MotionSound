package com.motionsound.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionsound.data.PlaylistRepository
import com.motionsound.data.SoundPrefsStore
import com.motionsound.data.SongRepository
import com.motionsound.model.Playlist
import com.motionsound.model.Song
import com.motionsound.stem.LoopMode
import com.motionsound.stem.PlayerControlState
import com.motionsound.stem.PreCacheProgress
import com.motionsound.stem.StemPlayerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlayerUiState(
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLoading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val playingSongs: List<Song>? = null,
    val hasStartedPlayback: Boolean = false,
    val isShuffled: Boolean = false,
    val queueBeforeShuffle: List<Song>? = null,
    val loopMode: LoopMode = LoopMode.NONE,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val preCacheProgress: PreCacheProgress = PreCacheProgress(),
    val stemsReadyUris: Set<String> = emptySet(),
    val separatingUri: String? = null,
    val separationProgress: Float = 0f
) {
    val currentSong: Song?
        get() {
            if (!hasStartedPlayback || currentIndex < 0) return null
            return (playingSongs ?: songs).getOrNull(currentIndex)
        }

    fun playlistSongs(allSongs: List<Song> = songs): List<Song> {
        val pl = playlists.find { it.id == selectedPlaylistId } ?: return emptyList()
        return allSongs.filter { it.id in pl.songIds }
    }
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var stemService: StemPlayerService? = null
    private var stateJob: Job? = null
    private var positionJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? StemPlayerService.LocalBinder
            if (binder == null) {
                return
            }
            stemService = binder.getService()
            stemService?.updateLoopMode(_uiState.value.loopMode)
            seedStemsStatus(_uiState.value.songs)
            syncState()
            startStateCollection()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stemService = null
            viewModelScope.launch {
                delay(2000)
                if (stemService == null) {
                    try {
                        val app = getApplication<Application>()
                        val intent = Intent(app, StemPlayerService::class.java)
                        app.startForegroundService(intent)
                        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    init {
        loadSongs()
        loadPlaylists()
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = SoundPrefsStore.load(app)
                if (p != null) {
                    val loop = if (p.loopRepeatAll) LoopMode.REPEAT_ALL
                    else if (p.loopMode) LoopMode.REPEAT_ONE else LoopMode.NONE
                    _uiState.value = _uiState.value.copy(loopMode = loop)
                    stemService?.updateLoopMode(loop)
                }
            } catch (_: Exception) {
            }
        }
        try {
            val intent = Intent(app, StemPlayerService::class.java)
            app.startForegroundService(intent)
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
        }
    }

    private fun startStateCollection() {
        stateJob?.cancel()
        stateJob = viewModelScope.launch {
            val s = stemService ?: return@launch
            launch {
                try {
                    s.playerState.collect { state ->
                        _uiState.value = _uiState.value.copy(
                            currentIndex = state.currentIndex,
                            isPlaying = state.isPlaying,
                            durationMs = state.durationMs,
                            hasStartedPlayback = _uiState.value.hasStartedPlayback || state.currentIndex >= 0,
                            hasNext = state.hasNext,
                            hasPrevious = state.hasPrevious
                        )
                        if (state.isPlaying) startPositionUpdates()
                    }
                } catch (e: Exception) {
                }
            }
            launch {
                try {
                    s.preCacheProgress.collect { progress ->
                        _uiState.value = _uiState.value.copy(preCacheProgress = progress)
                    }
                } catch (e: Exception) {
                }
            }
            launch {
                try {
                    s.separatedUris.collect { uris ->
                        _uiState.value = _uiState.value.copy(
                            stemsReadyUris = _uiState.value.stemsReadyUris + uris
                        )
                    }
                } catch (e: Exception) {
                }
            }
            launch {
                try {
                    s.separatingUri.collect { uri ->
                        _uiState.value = _uiState.value.copy(separatingUri = uri)
                    }
                } catch (e: Exception) {
                }
            }
            launch {
                try {
                    s.separationProgress.collect { p ->
                        _uiState.value = _uiState.value.copy(separationProgress = p)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun syncState() {
        val s = stemService ?: return
        val state = s.playerState.value
        _uiState.value = _uiState.value.copy(
            currentIndex = state.currentIndex,
            isPlaying = state.isPlaying,
            durationMs = state.durationMs,
            hasNext = state.hasNext,
            hasPrevious = state.hasPrevious
        )
        if (state.isPlaying) startPositionUpdates()
    }

    private fun loadSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = SongRepository.loadSongs(getApplication())
            prunePlaylists(songs)
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
            seedStemsStatus(songs)
        }
    }

    private fun seedStemsStatus(songs: List<Song>) {
        val s = stemService ?: return
        if (songs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ready = songs.filter { song ->
                runCatching { s.hasCachedStems(android.net.Uri.parse(song.uri)) }.getOrDefault(false)
            }.map { it.uri }.toSet()
            if (ready.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(stemsReadyUris = _uiState.value.stemsReadyUris + ready)
            }
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pl = PlaylistRepository.load(getApplication())
                _uiState.value = _uiState.value.copy(playlists = pl)
            } catch (e: Exception) {
            }
        }
    }

    private fun savePlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PlaylistRepository.save(getApplication(), _uiState.value.playlists)
            } catch (_: Exception) {
            }
        }
    }

    private fun prunePlaylists(liveSongs: List<Song>) {
        val liveIds = liveSongs.map { it.id }.toSet()
        var changed = false
        val pruned = _uiState.value.playlists.map { pl ->
            val filtered = pl.songIds.filter { it in liveIds }
            if (filtered.size != pl.songIds.size) {
                changed = true
                pl.copy(songIds = filtered)
            } else pl
        }
        if (changed) {
            _uiState.value = _uiState.value.copy(playlists = pruned)
            savePlaylists()
        }
    }

    fun playSong(index: Int) {
        val s = stemService ?: return
        val state = _uiState.value
        val plSongs = if (state.selectedPlaylistId != null) state.playlistSongs() else null
        val targetSongs = plSongs ?: state.songs
        if (index !in targetSongs.indices) return
        val song = targetSongs[index]
        s.setMetadata(song.title, song.artist)
        val uris = targetSongs.map { it.uri }
        s.setPlaylist(uris, index)
        _uiState.value = state.copy(
            currentIndex = index,
            playingSongs = plSongs,
            hasStartedPlayback = true
        )
        startPositionUpdates()
    }

    fun cycleLoop() {
        val next = when (_uiState.value.loopMode) {
            LoopMode.NONE -> LoopMode.REPEAT_ALL
            LoopMode.REPEAT_ALL -> LoopMode.REPEAT_ONE
            LoopMode.REPEAT_ONE -> LoopMode.NONE
        }
        _uiState.value = _uiState.value.copy(loopMode = next)
        stemService?.updateLoopMode(next)
    }

    fun toggleShuffle() {
        val current = _uiState.value
        val source = current.playingSongs ?: current.songs
        val currentSong = current.currentSong ?: return
        if (current.isShuffled) {
            val prevQueue = current.queueBeforeShuffle
            val restore = prevQueue ?: current.songs
            val newIndex = restore.indexOfFirst { it.id == currentSong.id }
                .coerceAtLeast(0)
            val uris = restore.map { it.uri }
            stemService?.setPlaylist(uris, newIndex)
            _uiState.value = current.copy(
                isShuffled = false,
                playingSongs = prevQueue,
                queueBeforeShuffle = null,
                currentIndex = newIndex
            )
        } else {
            val shuffled = source.toMutableList()
            shuffled.removeAll { it.id == currentSong.id }
            shuffled.shuffle()
            shuffled.add(0, currentSong)
            val uris = shuffled.map { it.uri }
            stemService?.setPlaylist(uris, 0)
            _uiState.value = current.copy(
                isShuffled = true,
                playingSongs = shuffled,
                queueBeforeShuffle = if (current.playingSongs != null) current.playingSongs else null,
                currentIndex = 0
            )
        }
    }

    fun playShuffled(songs: List<Song>) {
        val s = stemService ?: return
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        val first = shuffled.first()
        s.setMetadata(first.title, first.artist)
        val uris = shuffled.map { it.uri }
        s.setPlaylist(uris, 0)
        _uiState.value = _uiState.value.copy(
            currentIndex = 0,
            playingSongs = shuffled,
            queueBeforeShuffle = songs,
            hasStartedPlayback = true
        )
        startPositionUpdates()
    }

    fun playQueueIndex(index: Int) {
        val s = stemService ?: return
        val state = _uiState.value
        val targetSongs = state.playingSongs ?: state.songs
        if (index !in targetSongs.indices) return
        val song = targetSongs[index]
        s.setMetadata(song.title, song.artist)
        s.setPlaylist(targetSongs.map { it.uri }, index)
        _uiState.value = state.copy(currentIndex = index, hasStartedPlayback = true)
        startPositionUpdates()
    }

    fun playNextSong(song: Song) {
        val s = stemService ?: return
        val state = _uiState.value
        val targetSongs = state.playingSongs ?: state.songs
        if (targetSongs.isEmpty() || song.id == state.currentSong?.id) return
        val newQueue = targetSongs.filterNot { it.id == song.id }.toMutableList()
        val curPos = newQueue.indexOfFirst { it.id == (state.currentSong?.id ?: "") }
        val insertPos = (if (curPos >= 0) curPos + 1 else newQueue.size).coerceIn(0, newQueue.size)
        newQueue.add(insertPos, song)
        val current = curPos.coerceAtLeast(0)
        s.setPlaylist(newQueue.map { it.uri }, current)
        _uiState.value = state.copy(playingSongs = newQueue, currentIndex = current)
    }

    fun addToQueue(song: Song) {
        val s = stemService ?: return
        val state = _uiState.value
        val targetSongs = state.playingSongs ?: state.songs
        if (targetSongs.any { it.id == song.id }) return
        val newQueue = targetSongs + song
        s.setPlaylist(newQueue.map { it.uri }, state.currentIndex.coerceIn(0, newQueue.size - 1))
        _uiState.value = state.copy(playingSongs = newQueue)
    }

    fun togglePlayPause() {
        stemService?.togglePlayPause()
    }

    fun playNext() {
        stemService?.playNext()
    }

    fun playPrevious() {
        stemService?.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        stemService?.seekTo(positionMs)
    }

    fun createPlaylist(name: String): String? {
        val playlist = Playlist(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            songIds = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            playlists = _uiState.value.playlists + playlist
        )
        savePlaylists()
        return playlist.id
    }

    fun addSongToPlaylist(playlistId: String, songId: Long) {
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id == playlistId && songId !in pl.songIds) {
                pl.copy(songIds = pl.songIds + songId)
            } else pl
        }
        _uiState.value = _uiState.value.copy(playlists = updated)
        savePlaylists()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: Long) {
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(songIds = pl.songIds - songId)
            } else pl
        }
        _uiState.value = _uiState.value.copy(playlists = updated)
        savePlaylists()
    }

    fun deletePlaylist(playlistId: String) {
        _uiState.value = _uiState.value.copy(
            playlists = _uiState.value.playlists.filter { it.id != playlistId },
            selectedPlaylistId = if (_uiState.value.selectedPlaylistId == playlistId) null
                else _uiState.value.selectedPlaylistId
        )
        savePlaylists()
    }

    fun selectPlaylist(playlistId: String?) {
        _uiState.value = _uiState.value.copy(selectedPlaylistId = playlistId)
    }

    fun preProcessPlaylist() {
        val s = stemService ?: return
        val state = _uiState.value
        val pl = state.playlists.find { it.id == state.selectedPlaylistId } ?: return
        val uris = state.songs.filter { it.id in pl.songIds }.map { it.uri }
        if (uris.isNotEmpty()) s.processPlaylist(uris)
    }

    fun preProcessSong(songId: Long) {
        val s = stemService ?: return
        val song = _uiState.value.songs.find { it.id == songId } ?: return
        if (song.uri in _uiState.value.stemsReadyUris) return
        s.processPlaylist(listOf(song.uri))
    }

    fun preProcessMissingSongs() {
        val s = stemService ?: return
        val missing = _uiState.value.songs
            .filter { it.uri !in _uiState.value.stemsReadyUris }
            .map { it.uri }
        if (missing.isNotEmpty()) s.processPlaylist(missing)
    }

    fun cancelPreCache() {
        stemService?.cancelPreCache()
    }

    fun refreshSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val songs = SongRepository.loadSongs(getApplication())
            prunePlaylists(songs)
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
            seedStemsStatus(songs)
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (_uiState.value.isPlaying && stemService != null) {
                try {
                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = stemService?.getCurrentPosition() ?: 0L
                    )
                    delay(200)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(1000)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stemService?.cancelPreCache()
        stateJob?.cancel()
        positionJob?.cancel()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}

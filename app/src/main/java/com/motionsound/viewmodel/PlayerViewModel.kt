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
import com.motionsound.data.SongRepository
import com.motionsound.model.Playlist
import com.motionsound.model.Song
import com.motionsound.stem.PlayerControlState
import com.motionsound.stem.PreCacheProgress
import com.motionsound.stem.StemPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val preCacheProgress: PreCacheProgress = PreCacheProgress()
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
            stemService = (service as StemPlayerService.LocalBinder).getService()
            syncState()
            startStateCollection()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stemService = null
        }
    }

    init {
        loadSongs()
        loadPlaylists()
        val app = getApplication<Application>()
        try {
            val intent = Intent(app, StemPlayerService::class.java)
            app.startForegroundService(intent)
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Failed to start/bind StemPlayerService", e)
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
                            hasStartedPlayback = _uiState.value.hasStartedPlayback || state.currentIndex >= 0
                        )
                        if (state.isPlaying) startPositionUpdates()
                    }
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "playerState collect failed", e)
                }
            }
            launch {
                try {
                    s.preCacheProgress.collect { progress ->
                        _uiState.value = _uiState.value.copy(preCacheProgress = progress)
                    }
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "preCacheProgress collect failed", e)
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
            durationMs = state.durationMs
        )
        if (state.isPlaying) startPositionUpdates()
    }

    private fun loadSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = SongRepository.loadSongs(getApplication())
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pl = PlaylistRepository.load(getApplication())
                _uiState.value = _uiState.value.copy(playlists = pl)
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to load playlists", e)
            }
        }
    }

    private fun savePlaylists() {
        PlaylistRepository.save(getApplication(), _uiState.value.playlists)
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
        s.cancelPreCache()
        s.setPlaylist(uris, index)
        if (plSongs != null) s.preCachePlaylist(uris)
        _uiState.value = state.copy(
            currentIndex = index,
            playingSongs = plSongs,
            hasStartedPlayback = true
        )
        startPositionUpdates()
    }

    fun toggleShuffle() {
        val current = _uiState.value
        val source = current.playingSongs ?: current.songs
        val currentSong = current.currentSong ?: return
        if (current.isShuffled) {
            val newIndex = current.songs.indexOfFirst { it.id == currentSong.id }
                .coerceAtLeast(0)
            val uris = current.songs.map { it.uri }
            stemService?.setPlaylist(uris, newIndex)
            _uiState.value = current.copy(
                isShuffled = false, playingSongs = null, currentIndex = newIndex
            )
        } else {
            val shuffled = source.toMutableList()
            shuffled.removeAll { it.id == currentSong.id }
            shuffled.shuffle()
            shuffled.add(0, currentSong)
            val uris = shuffled.map { it.uri }
            stemService?.setPlaylist(uris, 0)
            _uiState.value = current.copy(
                isShuffled = true, playingSongs = shuffled, currentIndex = 0
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
        s.cancelPreCache()
        s.setPlaylist(uris, 0)
        s.preCachePlaylist(uris)
        _uiState.value = _uiState.value.copy(currentIndex = 0, playingSongs = shuffled, hasStartedPlayback = true)
        startPositionUpdates()
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

    fun createPlaylist(name: String) {
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
        stemService?.cancelPreCache()
        _uiState.value = _uiState.value.copy(selectedPlaylistId = playlistId)
    }

    fun refreshSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val songs = SongRepository.loadSongs(getApplication())
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                try {
                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = stemService?.getCurrentPosition() ?: 0L
                    )
                    delay(200)
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Position update failed", e)
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

package com.motionsound.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionsound.data.PlaylistRepository
import com.motionsound.data.SongRepository
import com.motionsound.model.Playlist
import com.motionsound.model.Song
import com.motionsound.service.CustomPlayer
import com.motionsound.service.MusicService
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
    val hasStartedPlayback: Boolean = false
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

    private var player: CustomPlayer? = null
    private var stateJob: Job? = null
    private var positionJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            player = (service as MusicService.PlayerBinder).getPlayer()
            syncState()
            startStateCollection()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            player = null
        }
    }

    init {
        loadSongs()
        loadPlaylists()
        val app = getApplication<Application>()
        val intent = Intent(app, MusicService::class.java)
        app.startForegroundService(intent)
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun startStateCollection() {
        stateJob?.cancel()
        stateJob = viewModelScope.launch {
            val p = player ?: return@launch
            p.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    currentIndex = state.currentIndex,
                    isPlaying = state.isPlaying,
                    durationMs = state.durationMs,
                    hasStartedPlayback = _uiState.value.hasStartedPlayback || state.currentIndex >= 0
                )
                if (state.isPlaying) startPositionUpdates()
            }
        }
    }

    private fun syncState() {
        val p = player ?: return
        val state = p.state.value
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
        val pl = PlaylistRepository.load(getApplication())
        _uiState.value = _uiState.value.copy(playlists = pl)
    }

    private fun savePlaylists() {
        PlaylistRepository.save(getApplication(), _uiState.value.playlists)
    }

    fun playSong(index: Int) {
        val p = player ?: return
        val songs = _uiState.value.songs
        if (index !in songs.indices) return
        val uris = songs.map { it.uri }
        p.setPlaylist(uris, index)
        _uiState.value = _uiState.value.copy(currentIndex = index, playingSongs = null, hasStartedPlayback = true)
        startPositionUpdates()
    }

    fun playShuffled(songs: List<Song>) {
        val p = player ?: return
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        val uris = shuffled.map { it.uri }
        p.setPlaylist(uris, 0)
        _uiState.value = _uiState.value.copy(currentIndex = 0, playingSongs = shuffled, hasStartedPlayback = true)
        startPositionUpdates()
    }

    fun togglePlayPause() {
        player?.togglePlayPause()
    }

    fun playNext() {
        player?.playNext()
    }

    fun playPrevious() {
        player?.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
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
        _uiState.value = _uiState.value.copy(selectedPlaylistId = playlistId)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = player?.getCurrentPosition() ?: 0L
                )
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stateJob?.cancel()
        positionJob?.cancel()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}

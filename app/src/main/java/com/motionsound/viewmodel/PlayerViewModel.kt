package com.motionsound.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.motionsound.data.SongRepository
import com.motionsound.model.Song
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
    val isLoading: Boolean = true
) {
    val currentSong: Song?
        get() = songs.getOrNull(currentIndex)
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _uiState.value = _uiState.value.copy(durationMs = duration)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }
        })
    }

    private var positionJob: Job? = null

    init {
        loadSongs()
    }

    private fun loadSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = SongRepository.loadSongs(getApplication())
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
        }
    }

    fun playSong(index: Int) {
        val song = _uiState.value.songs.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(currentIndex = index)
        val mediaItem = MediaItem.fromUri(song.uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        startPositionUpdates()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun playNext() {
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return
        val next = (_uiState.value.currentIndex + 1).coerceAtMost(songs.size - 1)
        playSong(next)
    }

    fun playPrevious() {
        val prev = _uiState.value.currentIndex - 1
        if (prev >= 0) playSong(prev)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = player.currentPosition
                )
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}

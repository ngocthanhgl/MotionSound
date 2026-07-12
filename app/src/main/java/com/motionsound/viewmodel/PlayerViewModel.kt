package com.motionsound.viewmodel

import android.app.Application
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.motionsound.data.SongRepository
import com.motionsound.model.Song
import com.motionsound.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

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

    private var mediaController: MediaController? = null
    private var positionJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _uiState.value = _uiState.value.copy(
                    durationMs = mediaController?.duration ?: 0L
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = mediaController?.currentMediaItemIndex ?: -1
            _uiState.value = _uiState.value.copy(currentIndex = index)
        }
    }

    init {
        loadSongs()
        connectToService()
    }

    private fun connectToService() {
        val app = getApplication<Application>()
        val sessionToken = SessionToken(app, ComponentName(app, MusicService::class.java))
        val controllerFuture = MediaController.Builder(app, sessionToken).buildAsync()
        val mainExecutor = Executor { Handler(Looper.getMainLooper()).post(it) }
        controllerFuture.addListener({
            val ctrl = controllerFuture.get()
            mediaController?.removeListener(playerListener)
            mediaController = ctrl.apply { addListener(playerListener) }
            syncState()
        }, mainExecutor)
    }

    private fun syncState() {
        val ctrl = mediaController ?: return
        _uiState.value = _uiState.value.copy(
            currentIndex = ctrl.currentMediaItemIndex,
            isPlaying = ctrl.isPlaying,
            durationMs = ctrl.duration
        )
        if (ctrl.isPlaying) startPositionUpdates()
    }

    private fun loadSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = SongRepository.loadSongs(getApplication())
            _uiState.value = _uiState.value.copy(songs = songs, isLoading = false)
        }
    }

    fun playSong(index: Int) {
        val controller = mediaController ?: return
        val songs = _uiState.value.songs
        if (index !in songs.indices) return

        val mediaItems = songs.map { MediaItem.fromUri(it.uri) }
        controller.setMediaItems(mediaItems, index, 0L)
        controller.prepare()
        controller.play()
        _uiState.value = _uiState.value.copy(currentIndex = index)
        startPositionUpdates()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun playNext() {
        mediaController?.let { ctrl ->
            if (ctrl.hasNextMediaItem()) ctrl.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        mediaController?.let { ctrl ->
            if (ctrl.hasPreviousMediaItem()) ctrl.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = mediaController?.currentPosition ?: 0L
                )
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaController?.removeListener(playerListener)
    }
}

package com.motionsound.service

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerControlState(
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val audioSessionId: Int = 0
)

class CustomPlayer(private val context: Context) {

    private val mp = MediaPlayer()
    private var playlist = listOf<String>()
    private var currentIndex = -1
    private var isPrepared = false

    private val _state = MutableStateFlow(PlayerControlState())
    val state: StateFlow<PlayerControlState> = _state.asStateFlow()

    val audioSessionId: Int get() = _state.value.audioSessionId

    init {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessionId = am.generateAudioSessionId()
        mp.setAudioSessionId(sessionId)
        mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        _state.value = _state.value.copy(audioSessionId = sessionId)
    }

    private val preparedListener = MediaPlayer.OnPreparedListener {
        isPrepared = true
        _state.value = _state.value.copy(durationMs = it.duration.toLong())
        it.start()
        _state.value = _state.value.copy(isPlaying = true)
    }

    private val completionListener = MediaPlayer.OnCompletionListener {
        if (currentIndex + 1 in playlist.indices) {
            playAt(currentIndex + 1)
        } else {
            currentIndex = -1
            isPrepared = false
            _state.value = PlayerControlState(audioSessionId = _state.value.audioSessionId)
        }
    }

    private val errorListener = MediaPlayer.OnErrorListener { _, _, _ -> true }

    fun setPlaylist(uris: List<String>, startIndex: Int) {
        playlist = uris
        playAt(startIndex)
    }

    private fun playAt(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        isPrepared = false
        try {
            mp.reset()
            mp.setAudioSessionId(_state.value.audioSessionId)
            mp.setDataSource(context, Uri.parse(playlist[index]))
            mp.setOnPreparedListener(preparedListener)
            mp.setOnCompletionListener(completionListener)
            mp.setOnErrorListener(errorListener)
            mp.prepareAsync()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(currentIndex = index, durationMs = 0L)
    }

    fun play() {
        if (isPrepared && !mp.isPlaying) {
            mp.start()
            _state.value = _state.value.copy(isPlaying = true)
        }
    }

    fun pause() {
        if (isPrepared && mp.isPlaying) {
            mp.pause()
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun togglePlayPause() {
        if (isPrepared) {
            if (mp.isPlaying) pause() else play()
        }
    }

    fun playNext() {
        if (currentIndex + 1 in playlist.indices) playAt(currentIndex + 1)
    }

    fun playPrevious() {
        if (currentIndex - 1 in playlist.indices) playAt(currentIndex - 1)
    }

    fun seekTo(positionMs: Long) {
        if (isPrepared) mp.seekTo(positionMs.toInt())
    }

    fun getCurrentPosition(): Long = if (isPrepared) mp.currentPosition.toLong() else 0L

    fun hasNext(): Boolean = currentIndex + 1 in playlist.indices
    fun hasPrevious(): Boolean = currentIndex - 1 in playlist.indices

    fun release() {
        mp.release()
    }
}

package com.motionsound.drive

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionsound.sounddrive.SoundDriveMode
import com.motionsound.stem.StemPlayerService
import com.motionsound.stem.StemUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val _driveState = MutableStateFlow(StemUiState())
    val driveState: StateFlow<StemUiState> = _driveState.asStateFlow()

    private var stemService: StemPlayerService? = null
    private var bound = false
    private var collectJob: Job? = null

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StemPlayerService.LocalBinder ?: return
            stemService = binder.getService()
            bound = true
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                try {
                    stemService?.stemState?.collect { state ->
                        _driveState.value = state
                    }
                } catch (e: Exception) {
                    Log.e("DriveViewModel", "State collection failed", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            stemService = null
            _driveState.value = StemUiState(modelError = "Service crashed â€” restartingâ€¦")
            viewModelScope.launch {
                delay(2000)
                if (!bound) startService()
            }
        }
    }

    init {
        startService()
    }

    fun startService() {
        try {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, StemPlayerService::class.java)
            ctx.startForegroundService(intent)
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
        }
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        if (bound) {
            try { ctx.unbindService(connection) } catch (e: Exception) { Log.e("DriveViewModel", "Unbind failed", e) }
            bound = false
        }
        ctx.stopService(Intent(ctx, StemPlayerService::class.java))
    }

    fun setVolumeDrums(v: Float) { stemService?.setManualVolumeDrums(v) }
    fun setVolumeBass(v: Float) { stemService?.setManualVolumeBass(v) }
    fun setVolumeOther(v: Float) { stemService?.setManualVolumeOther(v) }
    fun setVolumeVocals(v: Float) { stemService?.setManualVolumeVocals(v) }
    fun resetManualVolumes() { stemService?.resetManualVolumes() }

    fun setSoundDriveMode(mode: SoundDriveMode) { stemService?.soundDriveMode = mode }
    fun toggleSoundDrive() { stemService?.let { it.soundDriveEnabled = !it.soundDriveEnabled } }
    fun setSoundDriveGpsMode(enabled: Boolean) { stemService?.soundDriveGpsMode = enabled }

    override fun onCleared() {
        stopService()
        super.onCleared()
    }
}

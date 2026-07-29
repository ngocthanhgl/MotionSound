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
import com.motionsound.sounddrive.SensorProfile
import com.motionsound.sounddrive.SoundDriveMode
import com.motionsound.stem.StemPlayerService
import com.motionsound.stem.StemUiState
import kotlinx.coroutines.Job
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
            Log.e("DriveViewModel", "Failed to start service", e)
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

    fun setVolumeDrums(v: Float) { stemService?.mixer?.volumeDrums = v }
    fun setVolumeBass(v: Float) { stemService?.mixer?.volumeBass = v }
    fun setVolumeOther(v: Float) { stemService?.mixer?.volumeOther = v }
    fun setVolumeVocals(v: Float) { stemService?.mixer?.volumeVocals = v }

    fun setSoundDriveMode(mode: SoundDriveMode) { stemService?.soundDriveMode = mode }
    fun toggleSoundDrive() { stemService?.let { it.soundDriveEnabled = !it.soundDriveEnabled } }
    fun setSoundDriveIntensity(v: Float) { stemService?.soundDriveIntensity = v }
    fun setSensorProfile(profile: SensorProfile) { stemService?.sensorProfile = profile }
    fun setCustomFilterSweep(v: Float) { stemService?.setCustomFilterSweep(v) }
    fun setCustomPanDepth(v: Float) { stemService?.setCustomPanDepth(v) }
    fun setCustomAtmosphere(v: Float) { stemService?.setCustomAtmosphere(v) }
    fun setCustomLowCut(v: Float) { stemService?.setCustomLowCut(v) }

    override fun onCleared() {
        stopService()
        super.onCleared()
    }
}

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val _driveState = MutableStateFlow(DriveUiState())
    val driveState: StateFlow<DriveUiState> = _driveState.asStateFlow()

    private var pipeline: DrivePipeline? = null
    private var bound = false
    private var collectJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? DriveService.LocalBinder ?: return
            pipeline = binder.getPipeline()
            bound = true
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                try {
                    pipeline?.uiState?.collect { state ->
                        _driveState.value = state
                    }
                } catch (e: Exception) {
                    Log.e("DriveViewModel", "State collection failed", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            pipeline = null
        }
    }

    fun startService() {
        try {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, DriveService::class.java)
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
        ctx.stopService(Intent(ctx, DriveService::class.java))
    }

    fun setAccelSensitivity(v: Float) {
        pipeline?.setAccelSensitivity(v)
    }

    fun setCornerSensitivity(v: Float) {
        pipeline?.setCornerSensitivity(v)
    }

    fun setEffectDepth(v: Float) {
        pipeline?.setEffectDepth(v)
    }

    fun setResponseSpeed(v: Float) {
        pipeline?.setResponseSpeed(v)
    }

    fun setVehiclePreset(preset: VehiclePreset) {
        pipeline?.setVehiclePreset(preset)
    }

    fun setMaxSpeed(kmh: Int) {
        pipeline?.setMaxSpeed(kmh)
    }

    fun setSensorSensitivity(v: Float) {
        pipeline?.setSensorSensitivity(v)
    }

    override fun onCleared() {
        stopService()
        super.onCleared()
    }
}

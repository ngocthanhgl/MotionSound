package com.motionsound.drive

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val _driveState = MutableStateFlow(DriveUiState())
    val driveState: StateFlow<DriveUiState> = _driveState.asStateFlow()

    private var pipeline: DrivePipeline? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            pipeline = null
        }
    }

    fun startService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, DriveService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        if (bound) {
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        ctx.stopService(Intent(ctx, DriveService::class.java))
    }

    fun setAccelSensitivity(v: Float) {
        pipeline?.setAccelSensitivity(v)
        _driveState.value = _driveState.value.copy(accelSensitivity = v)
    }

    fun setCornerSensitivity(v: Float) {
        pipeline?.setCornerSensitivity(v)
        _driveState.value = _driveState.value.copy(cornerSensitivity = v)
    }

    fun setEffectDepth(v: Float) {
        pipeline?.setEffectDepth(v)
        _driveState.value = _driveState.value.copy(effectDepth = v)
    }

    fun setResponseSpeed(v: Float) {
        pipeline?.setResponseSpeed(v)
        _driveState.value = _driveState.value.copy(responseSpeed = v)
    }

    fun setBumpFilterStrength(v: Float) {
        pipeline?.setBumpFilterStrength(v)
        _driveState.value = _driveState.value.copy(bumpFilterStrength = v)
    }

    fun setVehiclePreset(preset: VehiclePreset) {
        pipeline?.setVehiclePreset(preset)
        _driveState.value = _driveState.value.copy(vehiclePreset = preset)
    }

    fun setMaxSpeed(kmh: Int) {
        pipeline?.setMaxSpeed(kmh)
        _driveState.value = _driveState.value.copy(maxSpeedKmh = kmh)
    }

    override fun onCleared() {
        stopService()
        super.onCleared()
    }
}

package com.motionsound.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.motionsound.drive.DrivingConfig
import com.motionsound.drive.VehiclePreset

object DrivePreferences {
    private val KEY_MAX_SPEED_KMH = intPreferencesKey("max_speed_kmh")
    private val KEY_ACCEL_SENSITIVITY = floatPreferencesKey("accel_sensitivity")
    private val KEY_CORNER_SENSITIVITY = floatPreferencesKey("corner_sensitivity")
    private val KEY_EFFECT_DEPTH = floatPreferencesKey("effect_depth")
    private val KEY_RESPONSE_SPEED = floatPreferencesKey("response_speed")
    private val KEY_VEHICLE_PRESET = stringPreferencesKey("vehicle_preset")
    private val KEY_SENSOR_SENSITIVITY = floatPreferencesKey("sensor_sensitivity")

    suspend fun getMaxSpeedKmh(context: Context): Int =
        context.dataStore.data.first()[KEY_MAX_SPEED_KMH] ?: DrivingConfig.DEFAULT_MAX_SPEED_KMH

    suspend fun getAccelSensitivity(context: Context): Float =
        context.dataStore.data.first()[KEY_ACCEL_SENSITIVITY] ?: 1f

    suspend fun getCornerSensitivity(context: Context): Float =
        context.dataStore.data.first()[KEY_CORNER_SENSITIVITY] ?: 1f

    suspend fun getEffectDepth(context: Context): Float =
        context.dataStore.data.first()[KEY_EFFECT_DEPTH] ?: 0.7f

    suspend fun getResponseSpeed(context: Context): Float =
        context.dataStore.data.first()[KEY_RESPONSE_SPEED] ?: 0.5f

    suspend fun getVehiclePreset(context: Context): VehiclePreset =
        try { VehiclePreset.valueOf(context.dataStore.data.first()[KEY_VEHICLE_PRESET] ?: VehiclePreset.CAR.name) }
        catch (_: IllegalArgumentException) { VehiclePreset.CAR }

    suspend fun getSensorSensitivity(context: Context): Float =
        context.dataStore.data.first()[KEY_SENSOR_SENSITIVITY] ?: 1f

    suspend fun setMaxSpeedKmh(context: Context, value: Int) {
        context.dataStore.edit { it[KEY_MAX_SPEED_KMH] = value }
    }

    suspend fun setAccelSensitivity(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_ACCEL_SENSITIVITY] = value }
    }

    suspend fun setCornerSensitivity(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_CORNER_SENSITIVITY] = value }
    }

    suspend fun setEffectDepth(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_EFFECT_DEPTH] = value }
    }

    suspend fun setResponseSpeed(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_RESPONSE_SPEED] = value }
    }

    suspend fun setVehiclePreset(context: Context, value: VehiclePreset) {
        context.dataStore.edit { it[KEY_VEHICLE_PRESET] = value.name }
    }

    suspend fun setSensorSensitivity(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_SENSOR_SENSITIVITY] = value }
    }
}

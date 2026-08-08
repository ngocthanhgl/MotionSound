package com.motionsound.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.motionsound.sounddrive.SensorProfile
import com.motionsound.sounddrive.SoundDriveConfig
import com.motionsound.sounddrive.SoundDriveMode
import kotlinx.coroutines.flow.first

object SoundPrefsStore {
    private val KEY_ENABLED = booleanPreferencesKey("sd_enabled")
    private val KEY_MODE = stringPreferencesKey("sd_mode")
    private val KEY_INTENSITY = floatPreferencesKey("sd_intensity")
    private val KEY_SENSOR_PROFILE = stringPreferencesKey("sd_sensor_profile")
    private val KEY_GPS = booleanPreferencesKey("sd_gps")
    private val KEY_LOOP = booleanPreferencesKey("sd_loop")
    private val KEY_VOL_DRUMS = floatPreferencesKey("sd_vol_drums")
    private val KEY_VOL_BASS = floatPreferencesKey("sd_vol_bass")
    private val KEY_VOL_OTHER = floatPreferencesKey("sd_vol_other")
    private val KEY_VOL_VOCALS = floatPreferencesKey("sd_vol_vocals")

    data class StoredPrefs(
        val config: SoundDriveConfig,
        val loopMode: Boolean,
        val volumeDrums: Float,
        val volumeBass: Float,
        val volumeOther: Float,
        val volumeVocals: Float
    )

    suspend fun load(context: Context): StoredPrefs? {
        val prefs = context.dataStore.data.first()
        if (!prefs.contains(KEY_ENABLED)) return null
        val mode = runCatching { SoundDriveMode.valueOf(prefs[KEY_MODE] ?: "DYNAMIC") }
            .getOrDefault(SoundDriveMode.DYNAMIC)
        val profile = runCatching { SensorProfile.valueOf(prefs[KEY_SENSOR_PROFILE] ?: "DYNAMIC") }
            .getOrDefault(SensorProfile.DYNAMIC)
        return StoredPrefs(
            config = SoundDriveConfig(
                enabled = prefs[KEY_ENABLED] ?: false,
                mode = mode,
                intensity = prefs[KEY_INTENSITY] ?: 0.7f,
                sensorProfile = profile,
                gpsMode = prefs[KEY_GPS] ?: false
            ),
            loopMode = prefs[KEY_LOOP] ?: false,
            volumeDrums = prefs[KEY_VOL_DRUMS] ?: 1f,
            volumeBass = prefs[KEY_VOL_BASS] ?: 1f,
            volumeOther = prefs[KEY_VOL_OTHER] ?: 1f,
            volumeVocals = prefs[KEY_VOL_VOCALS] ?: 1f
        )
    }

    suspend fun save(context: Context, prefs: StoredPrefs) {
        context.dataStore.edit { p ->
            p[KEY_ENABLED] = prefs.config.enabled
            p[KEY_MODE] = prefs.config.mode.name
            p[KEY_INTENSITY] = prefs.config.intensity
            p[KEY_SENSOR_PROFILE] = prefs.config.sensorProfile.name
            p[KEY_GPS] = prefs.config.gpsMode
            p[KEY_LOOP] = prefs.loopMode
            p[KEY_VOL_DRUMS] = prefs.volumeDrums
            p[KEY_VOL_BASS] = prefs.volumeBass
            p[KEY_VOL_OTHER] = prefs.volumeOther
            p[KEY_VOL_VOCALS] = prefs.volumeVocals
        }
    }
}

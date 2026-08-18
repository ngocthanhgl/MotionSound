package com.motionsound.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.motionsound.sounddrive.SoundDriveConfig
import com.motionsound.sounddrive.SoundDriveMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object SoundPrefsStore {
    private val KEY_ENABLED = booleanPreferencesKey("sd_enabled")
    private val KEY_MODE = stringPreferencesKey("sd_mode")
    private val KEY_GPS = booleanPreferencesKey("sd_gps")
    private val KEY_LOOP = booleanPreferencesKey("sd_loop")
    private val KEY_LOOP_ALL = booleanPreferencesKey("sd_loop_all")
    private val KEY_VOL_DRUMS = floatPreferencesKey("sd_vol_drums")
    private val KEY_VOL_BASS = floatPreferencesKey("sd_vol_bass")
    private val KEY_VOL_OTHER = floatPreferencesKey("sd_vol_other")
    private val KEY_VOL_VOCALS = floatPreferencesKey("sd_vol_vocals")
    private val KEY_DARK = booleanPreferencesKey("theme_dark")

    data class StoredPrefs(
        val config: SoundDriveConfig,
        val loopMode: Boolean,
        val loopRepeatAll: Boolean = false,
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
        return StoredPrefs(
            config = SoundDriveConfig(
                enabled = prefs[KEY_ENABLED] ?: false,
                mode = mode,
                gpsMode = prefs[KEY_GPS] ?: false
            ),
            loopMode = prefs[KEY_LOOP] ?: false,
            loopRepeatAll = prefs[KEY_LOOP_ALL] ?: false,
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
            p[KEY_GPS] = prefs.config.gpsMode
            p[KEY_LOOP] = prefs.loopMode
            p[KEY_LOOP_ALL] = prefs.loopRepeatAll
            p[KEY_VOL_DRUMS] = prefs.volumeDrums
            p[KEY_VOL_BASS] = prefs.volumeBass
            p[KEY_VOL_OTHER] = prefs.volumeOther
            p[KEY_VOL_VOCALS] = prefs.volumeVocals
        }
    }

    fun darkFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DARK] ?: false }

    suspend fun setDark(context: Context, dark: Boolean) {
        context.dataStore.edit { p -> p[KEY_DARK] = dark }
    }
}

package com.motionsound.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "motionsound_prefs")

object ThemeManager {
    private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
    private val KEY_AMOLED = booleanPreferencesKey("amoled_mode")

    fun getDarkModeFlow(context: Context): Flow<String> {
        return context.dataStore.data.map { it[KEY_DARK_MODE] ?: "system" }
    }

    fun getAmoledFlow(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { it[KEY_AMOLED] ?: false }
    }

    suspend fun setDarkMode(context: Context, mode: String) {
        context.dataStore.edit { it[KEY_DARK_MODE] = mode }
    }

    suspend fun setAmoled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_AMOLED] = enabled }
    }
}

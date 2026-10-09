package com.tradequest.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "tradequest_settings")

/**
 * Device-level preferences kept in DataStore (not the Room season database). Right now it
 * only holds the selected theme, read once at startup so the first frame already uses the
 * persisted theme.
 */
class PreferencesStore(private val context: Context) {

    /** Emits the stored theme id, or [DEFAULT_THEME] when unset/unreadable. */
    val themeId: Flow<String> = context.themeDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it[ThemeKey] ?: DEFAULT_THEME }

    /** One-shot read for startup; falls back to Dark on any failure. */
    suspend fun themeIdOnce(): String = runCatching { themeId.first() }.getOrDefault(DEFAULT_THEME)

    suspend fun setThemeId(value: String) {
        context.themeDataStore.edit { it[ThemeKey] = value }
    }

    companion object {
        /** The persisted key name, as specified. */
        const val THEME_KEY = "theme_id"
        const val DEFAULT_THEME = "DARK"

        private val ThemeKey = stringPreferencesKey(THEME_KEY)
    }
}

package me.nettrash.scan.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all user-tunable preferences. Mirrors the
 * iOS `ScanSettingsKey` enum + `@AppStorage` pattern: the keys live
 * here, every consumer (ScannerViewModel, the Settings screen, the
 * What's-New gate) reads off the same DataStore-backed flow.
 *
 * Defaults are deliberately conservative — a freshly upgraded user
 * sees the same scanner behaviour as before this screen existed.
 */

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scan_settings"
)

data class SettingsState(
    val hapticOnScan: Boolean = true,
    val soundOnScan: Boolean = false,
    val continuousScan: Boolean = false,
    val lastSeenVersion: String = ""
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val store: DataStore<Preferences> = context.settingsDataStore

    /// Flow of every preference combined. Subscribers (Settings UI,
    /// ScannerViewModel) `collectAsState()` it as a whole; updates are
    /// individual writes via the helpers below so a toggle change does
    /// not have to round-trip every other key.
    val state: Flow<SettingsState> = store.data.map { p ->
        SettingsState(
            hapticOnScan    = p[Keys.HAPTIC_ON_SCAN] ?: true,
            soundOnScan     = p[Keys.SOUND_ON_SCAN] ?: false,
            continuousScan  = p[Keys.CONTINUOUS_SCAN] ?: false,
            lastSeenVersion = p[Keys.LAST_SEEN_VERSION] ?: "",
        )
    }

    suspend fun setHapticOnScan(value: Boolean) =
        store.edit { it[Keys.HAPTIC_ON_SCAN] = value }

    suspend fun setSoundOnScan(value: Boolean) =
        store.edit { it[Keys.SOUND_ON_SCAN] = value }

    suspend fun setContinuousScan(value: Boolean) =
        store.edit { it[Keys.CONTINUOUS_SCAN] = value }

    suspend fun setLastSeenVersion(value: String) =
        store.edit { it[Keys.LAST_SEEN_VERSION] = value }

    private object Keys {
        val HAPTIC_ON_SCAN     = booleanPreferencesKey("haptic_on_scan")
        val SOUND_ON_SCAN      = booleanPreferencesKey("sound_on_scan")
        val CONTINUOUS_SCAN    = booleanPreferencesKey("continuous_scan")
        val LAST_SEEN_VERSION  = stringPreferencesKey("last_seen_version")
    }
}

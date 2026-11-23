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
    val lastSeenVersion: String = "",
    /**
     * On-device "what is this code?" explanations. On by default, matching
     * the iOS `@AppStorage` default — the engine is local, so there is no
     * consent question to answer first.
     */
    val assistantEnabled: Boolean = true,
    /** Anti-quishing checks on decoded codes. On by default. */
    val riskChecksEnabled: Boolean = true,
    /** Which scanner mode the Scan tab opens in. */
    val scanMode: ScanModePreference = ScanModePreference.CODES,
    /** Target language for the on-device translator. */
    val translateLanguage: String = "system",
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
            assistantEnabled = p[Keys.ASSISTANT_ENABLED] ?: true,
            riskChecksEnabled = p[Keys.RISK_CHECKS_ENABLED] ?: true,
            scanMode = p[Keys.SCAN_MODE]
                ?.let { ScanModePreference.entries.firstOrNull { m -> m.key == it } }
                ?: ScanModePreference.CODES,
            translateLanguage = p[Keys.TRANSLATE_LANGUAGE] ?: "system",
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

    suspend fun setAssistantEnabled(value: Boolean) =
        store.edit { it[Keys.ASSISTANT_ENABLED] = value }

    suspend fun setRiskChecksEnabled(value: Boolean) =
        store.edit { it[Keys.RISK_CHECKS_ENABLED] = value }

    suspend fun setScanMode(value: ScanModePreference) =
        store.edit { it[Keys.SCAN_MODE] = value.key }

    suspend fun setTranslateLanguage(value: String) =
        store.edit { it[Keys.TRANSLATE_LANGUAGE] = value }

    private object Keys {
        val HAPTIC_ON_SCAN      = booleanPreferencesKey("haptic_on_scan")
        val SOUND_ON_SCAN       = booleanPreferencesKey("sound_on_scan")
        val CONTINUOUS_SCAN     = booleanPreferencesKey("continuous_scan")
        val LAST_SEEN_VERSION   = stringPreferencesKey("last_seen_version")
        val ASSISTANT_ENABLED   = booleanPreferencesKey("assistant_enabled")
        val RISK_CHECKS_ENABLED = booleanPreferencesKey("risk_checks_enabled")
        val SCAN_MODE           = stringPreferencesKey("scan_mode")
        val TRANSLATE_LANGUAGE  = stringPreferencesKey("translate_language")
    }
}

/**
 * Which of the three things the Scan tab can do it opens in. Mirrors the
 * iOS `ScanMode` enum; stored as a stable string so adding a mode later
 * does not shift anyone's stored preference.
 */
enum class ScanModePreference(val key: String, val label: String, val footnote: String) {
    CODES("codes", "Codes", "Point at a barcode or QR to decode it."),
    TEXT("text", "Live Text", "Tap a recognised text block to copy, share or translate it. A single code is read straight away; with several, tap one."),
    BULK("bulk", "Bulk", "Keep scanning: every distinct code is collected and counted."),
}

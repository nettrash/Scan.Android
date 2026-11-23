package me.nettrash.scan.ui.components

import androidx.compose.runtime.compositionLocalOf
import me.nettrash.scan.data.settings.SettingsState

/**
 * The user's preferences as the result surfaces read them.
 *
 * iOS gets this for free: every `PayloadActionsView` reads
 * `@AppStorage(riskChecksEnabled)` / `assistantEnabled` / `translateLanguage`
 * directly, so the camera sheet, the History detail, a Universal Link and a
 * shared file all obey Settings → Insights. Android's [PayloadActions] is a
 * plain composable shown from four different hosts (scanner sheet, History
 * dialog, App-Links sheet, share-intake sheet), and threading three flags
 * through every one of them is how the History and deep-link sheets ended up
 * ignoring the toggles. Providing the snapshot once at the bottom-nav root
 * (`ui/MainScreen.kt`) and reading it here keeps every surface honest.
 *
 * The default is [SettingsState]'s own defaults (assistant on, safety check
 * on, device language) — what a fresh install shows before DataStore has
 * answered, and what previews and tests see.
 */
val LocalScanSettings = compositionLocalOf { SettingsState() }

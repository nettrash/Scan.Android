package me.nettrash.scan

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt application root. No WorkManager wiring — Scan doesn't schedule any
 * background work today. If you add `@HiltWorker` workers later, also add
 * the `hilt-work` + `work-runtime-ktx` dependencies and re-implement
 * `Configuration.Provider` here.
 */
@HiltAndroidApp
class ScanApplication : Application()

package me.nettrash.scan

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import me.nettrash.scan.widget.RecentScansWidgetSync

/**
 * Hilt application root. No WorkManager wiring of our own — Scan doesn't
 * schedule any background work. (WorkManager is on the classpath since 1.10
 * because Glance, the home-screen widget toolkit, uses it to run widget
 * sessions; it initialises itself through androidx.startup and runs only
 * Glance's work.) If you add `@HiltWorker` workers later, also add the
 * `hilt-work` + `work-runtime-ktx` dependencies and re-implement
 * `Configuration.Provider` here.
 */
@HiltAndroidApp
class ScanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Keep a placed Recent-scans widget in step with History while the
        // process runs; does nothing on a phone without the widget.
        RecentScansWidgetSync.startIfPlaced(this)
    }
}

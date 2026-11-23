package me.nettrash.scan.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import me.nettrash.scan.util.Haptics
import me.nettrash.scan.widget.ScanEntry
import me.nettrash.scan.widget.ScanIntents

/**
 * Quick Settings tile: one tap from the shade into the camera.
 *
 * The tile equivalent of iOS's `StartScanningIntent`
 * (`Scan/ScanIntents.swift`) — "Hey Siri, scan a code" reduced to a button.
 * It carries a [ScanEntry.Scanner] entry request
 * ([ScanIntents.launchIntent]) rather than a bare launcher intent, so it
 * still lands on the Scan tab when the app is already warm on History or
 * Settings: the request reaches the running activity through `onNewIntent`
 * and `MainScreen` switches tabs.
 *
 * Registered in `AndroidManifest.xml` with `BIND_QUICK_SETTINGS_TILE`,
 * `exported="true"` and the `QS_TILE` intent filter; the system holds the
 * bind permission, so no other app can drive it. The tile icon is its own
 * `@drawable/ic_tile_scan` — a 24 dp white-on-transparent vector, as the
 * tile guide asks. SystemUI tints tile icons to the tile's state, and the
 * app icon it would otherwise fall back to is an opaque adaptive icon, which
 * tinting turns into a featureless solid shape.
 */
class ScanTileService : TileService() {

    override fun onClick() {
        super.onClick()
        // Same feedback language as the iOS shortcut launch; silently no-ops
        // on devices with no vibrator.
        Haptics.success(this)
        openScanner()
    }

    /**
     * The `startActivityAndCollapse` overload that takes a [PendingIntent]
     * landed in API 34 (Android 14) — `minSdk` here is 28 — so the call is
     * gated on `UPSIDE_DOWN_CAKE`, and the pre-34 branch keeps the
     * long-standing `Intent` overload, which on those releases is the only
     * way for a tile to open an activity and collapse the shade.
     *
     * That `Intent` overload is deprecated, and it throws
     * `UnsupportedOperationException` for apps targeting 34+ — but only when
     * it runs on 34+, which the version gate rules out. Lint's
     * `StartActivityAndCollapseDeprecated` check does not follow the
     * `SDK_INT` gate, hence the suppression on this one method rather than
     * project-wide, alongside the Kotlin deprecation it pairs with.
     *
     * `MainActivity` is the app's launcher activity, which is the one case
     * the collapse-and-launch path always allows; a tile that opened a
     * non-launcher activity would additionally need
     * `ActivityOptions.makeLaunchBehind()` on API 34+.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openScanner() {
        val intent = ScanIntents.launchIntent(this, ScanEntry.Scanner)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Fixed request code — there is exactly one tile PendingIntent. */
        private const val REQUEST_CODE = 0x5CA4
    }
}

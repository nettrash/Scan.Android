package me.nettrash.scan.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Pulls a [Vibrator] off the system service registry and emits a
 * short success-style pulse. Mirrors iOS's
 * `UINotificationFeedbackGenerator.notificationOccurred(.success)`.
 *
 * - On Android 12+ (API 31) we go through [VibratorManager] because
 *   the older direct-context lookup is deprecated and on multi-motor
 *   devices it would only hit the default actuator.
 * - On API ≥ 26 we use a [VibrationEffect] (which respects the user's
 *   "Vibration intensity" accessibility setting); on older versions
 *   we fall back to the deprecated `vibrate(ms)` overload.
 *
 * Calls are best-effort: a device with no vibrator (rare on phones,
 * common on cheap tablets) silently no-ops, just like the iOS path
 * silently no-ops on Macs running Catalyst.
 */
object Haptics {
    fun success(context: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return

        // The vibrate() Binder call asserts android.permission.VIBRATE
        // server-side and throws SecurityException on miss. AndroidManifest.xml
        // declares it (it's a normal-protection permission, granted at
        // install time), but some OEM-stripped ROMs and locked-down
        // enterprise profiles can revoke it. Wrap the calls so a denied
        // vibrate stays a no-op feedback failure rather than crashing
        // the whole scanner.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ~25 ms tick that matches the feel of a notification ping.
                val effect = VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25)
            }
        }
    }
}

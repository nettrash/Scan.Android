package me.nettrash.scan.ui.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The camera rationale shown before access is granted says everything the
 * camera is used for — codes, and printed text in Live Text — and that frames
 * stay on the device. The same sentence as iOS's `NSCameraUsageDescription`.
 */
class CameraRationaleTest {

    @Test fun rationaleIsTheSharedSentence() {
        assertThat(CAMERA_RATIONALE).isEqualTo(
            "Scan uses the camera to read barcodes and QR codes and, in Live Text mode, " +
                "printed text. Frames are processed on this device and are not saved."
        )
    }

    @Test fun rationaleMentionsTextAsWellAsCodes() {
        assertThat(CAMERA_RATIONALE).contains("barcodes")
        assertThat(CAMERA_RATIONALE).contains("Live Text")
        assertThat(CAMERA_RATIONALE).contains("printed text")
        assertThat(CAMERA_RATIONALE).contains("on this device")
    }
}

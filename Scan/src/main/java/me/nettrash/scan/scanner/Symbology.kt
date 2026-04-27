package me.nettrash.scan.scanner

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Maps the ML Kit `Barcode.format` constants to a small enum with display
 * names. Mirrors the Symbology enum in the iOS app.
 */
enum class Symbology(val displayName: String) {
    QR("QR"),
    AZTEC("Aztec"),
    PDF417("PDF417"),
    DATA_MATRIX("Data Matrix"),
    EAN8("EAN-8"),
    EAN13("EAN-13"),
    UPCE("UPC-E"),
    UPCA("UPC-A"),
    CODE39("Code 39"),
    CODE93("Code 93"),
    CODE128("Code 128"),
    ITF14("ITF-14"),
    INTERLEAVED_2_OF_5("Interleaved 2 of 5"),
    CODABAR("Codabar"),
    GS1_DATABAR("GS1 DataBar"),
    UNKNOWN("Unknown");

    /** Two-dimensional symbologies — used by UI hints. */
    val is2D: Boolean
        get() = this == QR || this == AZTEC || this == PDF417 || this == DATA_MATRIX

    companion object {
        /** Convert from an ML Kit `Barcode.format` constant. */
        fun fromMlKit(format: Int): Symbology = when (format) {
            Barcode.FORMAT_QR_CODE -> QR
            Barcode.FORMAT_AZTEC -> AZTEC
            Barcode.FORMAT_PDF417 -> PDF417
            Barcode.FORMAT_DATA_MATRIX -> DATA_MATRIX
            Barcode.FORMAT_EAN_8 -> EAN8
            Barcode.FORMAT_EAN_13 -> EAN13
            Barcode.FORMAT_UPC_E -> UPCE
            Barcode.FORMAT_UPC_A -> UPCA
            Barcode.FORMAT_CODE_39 -> CODE39
            Barcode.FORMAT_CODE_93 -> CODE93
            Barcode.FORMAT_CODE_128 -> CODE128
            Barcode.FORMAT_ITF -> ITF14
            Barcode.FORMAT_CODABAR -> CODABAR
            else -> UNKNOWN
        }

        /** Resolve a stored `displayName` (used when re-hydrating history). */
        fun fromDisplayName(name: String?): Symbology {
            if (name == null) return UNKNOWN
            return values().firstOrNull { it.displayName == name } ?: UNKNOWN
        }
    }
}

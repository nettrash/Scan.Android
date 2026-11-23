package me.nettrash.scan.data.payload

//
//  The value the Generate tab's "describe the code" box produces, whichever
//  engine produced it. Shared by the on-device model (Assistant.kt) and the
//  rule-based fallback (HeuristicExplainer.kt) so the form UI has one thing to
//  apply, and so the fallback can be tested without the language model in the
//  picture. Port of the iOS app's `Scan/ComposeResult.swift`.
//

/** Which form the composed values belong to. Mirrors iOS `ComposeKind`. */
enum class ComposeKind { TEXT, URL, WIFI, CONTACT, EMAIL, PHONE, PAYMENT, PRODUCT }

/**
 * Fields + literal text the Generate tab should apply. [values] keys are
 * always lowercase so [value] can look one up without caring how the engine
 * spelled it.
 */
data class ComposeResult(
    val kind: ComposeKind,
    val values: Map<String, String>,
    val text: String,
    val source: ComposeSource,
) {
    /** Which engine produced this — surfaced in the UI, same as iOS. */
    enum class ComposeSource { ON_DEVICE_MODEL, BUILT_IN }

    val isBlank: Boolean
        get() = text.trim().isEmpty() && values.isEmpty()

    fun value(key: String): String? {
        val v = values[key.lowercase()]?.trim()
        return if (v.isNullOrEmpty()) null else v
    }
}

package me.nettrash.scan.data.payload

/**
 * One row displayed in the result sheet. The UI shows `label` on the left,
 * `value` on the right, and a tap-to-copy button.
 */
data class LabelledField(val label: String, val value: String)

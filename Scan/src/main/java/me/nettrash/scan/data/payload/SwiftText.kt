package me.nettrash.scan.data.payload

import java.text.BreakIterator
import java.util.Locale

/**
 * Text measured the way the iOS port measures it, so a length rule means the
 * same on both: in user-perceived characters (extended grapheme clusters),
 * which is what a Swift `String` counts — "👍🏽" is one, not four UTF-16 units,
 * and a Thai syllable with its vowel marks is one.
 */
internal object SwiftText {

    /** Swift's `count`. */
    fun count(text: String): Int {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(text)
        var count = 0
        while (breaks.next() != BreakIterator.DONE) count += 1
        return count
    }

    /** The first [limit] grapheme clusters of [text] — Swift's `prefix(_:)`. */
    fun prefix(text: String, limit: Int): String {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaks.setText(text)
        var end = 0
        repeat(limit) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }
}

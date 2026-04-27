package me.nettrash.scan.data.payload

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parsed iCalendar VEVENT payload. Mirrors the iOS struct of the same name.
 *
 * `startDate` / `endDate` are the wall-clock instants the event covers, in
 * the device's local time zone (or UTC for `Z`-suffixed values).
 */
data class CalendarPayload(
    val summary: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val organizer: String?,
    val url: String?,
    val raw: String
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        summary?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Title", it) }
        startMillis?.let { rows += LabelledField("Start", format(it, allDay)) }
        endMillis?.let { rows += LabelledField("End", format(it, allDay)) }
        location?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Location", it) }
        organizer?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Organizer", it) }
        url?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("URL", it) }
        description?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Description", it) }
        return rows
    }

    private fun format(millis: Long, allDay: Boolean): String {
        val style = if (allDay) DateFormat.MEDIUM else DateFormat.MEDIUM
        val df = if (allDay) {
            DateFormat.getDateInstance(style, Locale.getDefault())
        } else {
            DateFormat.getDateTimeInstance(style, DateFormat.SHORT, Locale.getDefault())
        }
        return df.format(Date(millis))
    }
}

object CalendarPayloadParser {

    /** Quick check: does this look like an iCalendar payload? */
    fun looksLikeICalendar(s: String): Boolean {
        val lower = s.lowercase(Locale.ROOT)
        return lower.startsWith("begin:vcalendar") || lower.startsWith("begin:vevent")
    }

    /**
     * Parse a VEVENT (or full VCALENDAR) into a CalendarPayload. Returns null
     * only if the input doesn't contain a recognisable event block.
     */
    fun parse(raw: String): CalendarPayload? {
        val unfolded = unfold(raw)
        val lines = unfolded.split('\n')

        var inEvent = false
        val props = mutableListOf<Triple<String, Map<String, String>, String>>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true; continue
            }
            if (trimmed.equals("END:VEVENT", ignoreCase = true)) break
            if (!inEvent && trimmed.uppercase().startsWith("BEGIN:V")) continue
            if (!inEvent && !trimmed.uppercase().startsWith("END:")) continue
            if (!inEvent) continue
            if (trimmed.isEmpty()) continue
            parseLine(trimmed)?.let { props += it }
        }
        if (props.isEmpty()) {
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty() ||
                    t.uppercase().startsWith("BEGIN:") ||
                    t.uppercase().startsWith("END:")
                ) continue
                parseLine(t)?.let { props += it }
            }
        }
        if (props.isEmpty()) return null

        var summary: String? = null
        var dtStart: Pair<Long, Boolean>? = null
        var dtEnd: Pair<Long, Boolean>? = null
        var location: String? = null
        var description: String? = null
        var organizer: String? = null
        var url: String? = null

        for ((name, params, value) in props) {
            val text = unescapeText(value)
            when (name.uppercase()) {
                "SUMMARY" -> summary = text
                "LOCATION" -> location = text
                "DESCRIPTION" -> description = text
                "ORGANIZER" -> organizer = text.replace(Regex("(?i)mailto:"), "")
                "URL" -> url = text
                "DTSTART" -> dtStart = parseDate(value, params)
                "DTEND" -> dtEnd = parseDate(value, params)
            }
        }

        return CalendarPayload(
            summary = summary,
            startMillis = dtStart?.first,
            endMillis = dtEnd?.first,
            allDay = (dtStart?.second ?: false) || (dtEnd?.second ?: false),
            location = location,
            description = description,
            organizer = organizer,
            url = url,
            raw = raw
        )
    }

    /** RFC 5545 line folding: leading-whitespace lines continue the previous one. */
    private fun unfold(s: String): String {
        val normalised = s.replace("\r\n", "\n")
        val out = StringBuilder()
        for (line in normalised.split('\n')) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                out.append(line.substring(1))
            } else {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            }
        }
        return out.toString()
    }

    private fun parseLine(line: String): Triple<String, Map<String, String>, String>? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val pieces = head.split(';')
        val name = pieces.firstOrNull() ?: return null
        val params = mutableMapOf<String, String>()
        for (piece in pieces.drop(1)) {
            val eq = piece.indexOf('=')
            if (eq > 0) params[piece.substring(0, eq).uppercase()] = piece.substring(eq + 1)
        }
        return Triple(name, params, value)
    }

    private fun parseDate(value: String, params: Map<String, String>): Pair<Long, Boolean>? {
        val v = value.trim()
        val isAllDay = (params["VALUE"]?.uppercase() == "DATE") || v.length == 8

        if (isAllDay) {
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            return runCatching { fmt.parse(v)!!.time to true }.getOrNull()
        }
        if (v.endsWith("Z")) {
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return runCatching { fmt.parse(v)!!.time to false }.getOrNull()
        }
        val tz = params["TZID"]?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        for (pattern in listOf("yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm")) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }
            val d = runCatching { fmt.parse(v) }.getOrNull()
            if (d != null) return d.time to false
        }
        return null
    }

    /** Reverse iCalendar TEXT escaping (RFC 5545 § 3.3.11). */
    private fun unescapeText(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\' -> out.append('\\')
                    ';' -> out.append(';')
                    ',' -> out.append(',')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}

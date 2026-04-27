package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.TimeZone

/** RFC 5545 VEVENT coverage. Mirrors the iOS UTC + all-day cases. */
@RunWith(RobolectricTestRunner::class)
class CalendarParserTest {

    @Test fun parsesICalendarEvent() {
        val raw = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:abc-123
            SUMMARY:Quarterly review
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            LOCATION:Conference Room 4
            DESCRIPTION:Discuss Q1 plans
            ORGANIZER:mailto:alice@example.com
            URL:https://example.com/meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Calendar::class.java)
        val c = (p as ScanPayload.Calendar).payload
        assertThat(c.summary).isEqualTo("Quarterly review")
        assertThat(c.location).isEqualTo("Conference Room 4")
        assertThat(c.description).isEqualTo("Discuss Q1 plans")
        assertThat(c.organizer).isEqualTo("alice@example.com")
        assertThat(c.url).isEqualTo("https://example.com/meeting")
        assertThat(c.startMillis).isNotNull()
        assertThat(c.endMillis).isNotNull()
        // 2026-01-15 14:00 UTC.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = c.startMillis!!
        }
        assertThat(cal.get(Calendar.YEAR)).isEqualTo(2026)
        assertThat(cal.get(Calendar.MONTH)).isEqualTo(Calendar.JANUARY)
        assertThat(cal.get(Calendar.DAY_OF_MONTH)).isEqualTo(15)
        assertThat(cal.get(Calendar.HOUR_OF_DAY)).isEqualTo(14)
    }

    @Test fun parsesICalendarAllDayEvent() {
        val raw = """
            BEGIN:VEVENT
            SUMMARY:Holiday
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            END:VEVENT
        """.trimIndent()
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Calendar::class.java)
        val c = (p as ScanPayload.Calendar).payload
        assertThat(c.summary).isEqualTo("Holiday")
        assertThat(c.allDay).isTrue()
    }
}

package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ICalendarEventTest {
    private fun calendar(lines: String): String =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
            lines.replace("\n", "\r\n") +
            "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test fun parsesUtcEvent() {
        val event = ICalendarEvent.parse(calendar(
            "UID:test-1\nSUMMARY:Besprechung\nDTSTART:20261119T130000Z\n" +
                "DTEND:20261119T143000Z\nLOCATION:Raum 2"
        ))!!
        assertEquals("test-1", event.uid)
        assertEquals("Besprechung", event.title)
        assertEquals(Instant.parse("2026-11-19T13:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals(Instant.parse("2026-11-19T14:30:00Z").toEpochMilli(), event.endMillis)
        assertEquals("Raum 2", event.location)
        assertFalse(event.allDay)
    }

    @Test fun handlesEuropeBerlinTimeZone() {
        val result = ICalendarEvent.parse(calendar(
            "UID:tz-1\nSUMMARY:Termin\n" +
                "DTSTART;TZID=Europe/Berlin:20261119T130000\n" +
                "DTEND;TZID=Europe/Berlin:20261119T140000"
        ))!!
        assertEquals(Instant.parse("2026-11-19T12:00:00Z").toEpochMilli(), result.startMillis)
        assertEquals(Instant.parse("2026-11-19T13:00:00Z").toEpochMilli(), result.endMillis)
    }

    @Test fun handlesAllDayExclusiveEnd() {
        val result = ICalendarEvent.parse(calendar(
            "UID:day-1\nSUMMARY:Urlaub\nDTSTART;VALUE=DATE:20261224\nDTEND;VALUE=DATE:20261226"
        ))!!
        assertTrue(result.allDay)
        assertEquals(Instant.parse("2026-12-24T00:00:00Z").toEpochMilli(), result.startMillis)
        assertEquals(Instant.parse("2026-12-26T00:00:00Z").toEpochMilli(), result.endMillis)
    }

    @Test fun preservesFoldedAndEscapedText() {
        val result = ICalendarEvent.parse(calendar(
            "UID:fold-1\nSUMMARY:Kaffee mit\n Ada\nDESCRIPTION:Text\\nNeue Zeile\n" +
                "DTSTART:20261119T130000Z\nDTEND:20261119T133000Z"
        ))!!
        assertEquals("Kaffee mitAda", result.title)
        assertEquals("Text\nNeue Zeile", result.description)
    }

    @Test fun skipsRecurringAndAmbiguousEvents() {
        val base = "UID:repeat-1\nSUMMARY:Termin\nDTSTART:20261119T130000Z\nDTEND:20261119T140000Z"
        assertNull(ICalendarEvent.parse(calendar(base + "\nRRULE:FREQ=DAILY")))
        assertNull(ICalendarEvent.parse(calendar(base + "\nRECURRENCE-ID:20261119T130000Z")))
        assertNull(ICalendarEvent.parse(calendar(
            "UID:floating\nSUMMARY:Termin\nDTSTART:20261119T130000\nDTEND:20261119T140000"
        )))
        assertNull(ICalendarEvent.parse(calendar(
            "UID:dst\nSUMMARY:Termin\nDTSTART;TZID=Europe/Berlin:20261025T023000\n" +
                "DTEND;TZID=Europe/Berlin:20261025T033000"
        )))
        assertNull(ICalendarEvent.parse(calendar(
            "UID:bad\nSUMMARY:Termin\nDTSTART:20261119T140000Z\nDTEND:20261119T130000Z"
        )))
    }

    @Test fun skipsMultiEventFilesAndMissingFields() {
        assertNull(ICalendarEvent.parse(calendar(
            "UID:no-end\nSUMMARY:Termin\nDTSTART:20261119T130000Z"
        )))
        val duplicate = calendar("UID:one\nSUMMARY:First\nDTSTART:20261119T130000Z\nDTEND:20261119T140000Z")
            .replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:two\r\nEND:VEVENT\r\nEND:VCALENDAR")
        assertNull(ICalendarEvent.parse(duplicate))
        assertNull(ICalendarEvent.parse("garbage"))
    }
    @Test fun importsFiniteUtcRecurringEvents() {
        val event = ICalendarEvent.parse(calendar(
            "UID:daily-1\nSUMMARY:Daily check-in\nDTSTART:20261119T130000Z\n" +
                "DTEND:20261119T140000Z\nRRULE:FREQ=DAILY;COUNT=5"
        ))!!
        assertEquals("FREQ=DAILY;INTERVAL=1;COUNT=5", event.rrule)
        assertEquals(3600000L, event.endMillis - event.startMillis)
        assertFalse(event.allDay)
    }

    @Test fun importsFiniteAllDayRecurringEvents() {
        val event = ICalendarEvent.parse(calendar(
            "UID:monthly-1\nSUMMARY:Reminder\nDTSTART;VALUE=DATE:20261215\n" +
                "DTEND;VALUE=DATE:20261216\nRRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=6"
        ))!!
        assertEquals("FREQ=MONTHLY;INTERVAL=1;COUNT=6;BYMONTHDAY=15", event.rrule)
        assertTrue(event.allDay)
    }

    @Test fun refusesRecurrenceExceptionsAndTimeZoneAmbiguity() {
        val base = "UID:meeting\nSUMMARY:Meeting\nDTSTART:20261119T130000Z\n" +
            "DTEND:20261119T140000Z\nRRULE:FREQ=WEEKLY;COUNT=8"
        assertNull(ICalendarEvent.parse(calendar(base + "\nEXDATE:20261126T130000Z")))
        assertNull(ICalendarEvent.parse(calendar(base + "\nRECURRENCE-ID:20261119T130000Z")))
        assertNull(ICalendarEvent.parse(calendar(base.replace(
            "DTSTART:20261119T130000Z", "DTSTART;TZID=Europe/Berlin:20261119T140000"
        ))))
        assertNull(ICalendarEvent.parse(calendar(base.replace(
            "RRULE:FREQ=WEEKLY;COUNT=8", "RRULE:FREQ=WEEKLY"
        ))))
        assertNull(ICalendarEvent.parse(calendar(base.replace(
            "RRULE:FREQ=WEEKLY;COUNT=8", "RRULE:FREQ=WEEKLY;UNTIL=20261101T130000Z"
        ))))
    }

}

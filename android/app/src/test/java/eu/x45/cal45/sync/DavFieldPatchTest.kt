package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavFieldPatchTest {
    private fun prepared(result: DavFieldPatch.Result): DavFieldPatch.Result.Prepared {
        assertTrue("Expected prepared patch, got $result", result is DavFieldPatch.Result.Prepared)
        return result as DavFieldPatch.Result.Prepared
    }
    private fun rejected(result: DavFieldPatch.Result) {
        assertTrue("Expected rejection, got $result", result is DavFieldPatch.Result.Rejected)
    }

    @Test fun preservesUnknownVcardFieldsAndExactLineEndings() {
        val input = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:1234\r\n" +
            "FN:Ada Lovelace\r\nN:Lovelace;Ada;;;\r\n" +
            "PHOTO;ENCODING=b:ANONSECRET\r\n" +
            "X-CAL45-UNKNOWN:keep,me;exactly\r\nEND:VCARD\r\n"
        val result = prepared(DavFieldPatch.prepare(
            input, DavFieldPatch.Target.CONTACT_NAME, "Ada Lovelace", "Ada Byron"
        ))
        assertEquals(input.replace("FN:Ada Lovelace", "FN:Ada Byron"), result.patchedText)
        assertEquals(1, result.changedLines)
        assertTrue(result.patchedText.contains("X-CAL45-UNKNOWN:keep,me;exactly"))
        assertTrue(result.patchedText.contains("PHOTO;ENCODING=b:ANONSECRET"))
    }

    @Test fun preservesCalendarTimezoneAndUnknownProperties() {
        val input = "BEGIN:VCALENDAR\nVERSION:2.0\n" +
            "BEGIN:VTIMEZONE\nTZID:Europe/Berlin\nEND:VTIMEZONE\n" +
            "BEGIN:VEVENT\nUID:test\nSUMMARY:Meeting\n" +
            "DTSTART:20261119T130000Z\nDTEND:20261119T140000Z\n" +
            "ATTENDEE;CN=Ada:mailto:ada@example.org\nX-CUSTOM:keep-me\n" +
            "END:VEVENT\nEND:VCALENDAR\n"
        val result = prepared(DavFieldPatch.prepare(input,
            DavFieldPatch.Target.EVENT_TITLE, "Meeting", "Besprechung"
        ))
        assertEquals(input.replace("SUMMARY:Meeting", "SUMMARY:Besprechung"),
            result.patchedText)
        assertTrue(result.patchedText.contains("ATTENDEE;CN=Ada:mailto:ada@example.org"))
        assertTrue(result.patchedText.contains("VTIMEZONE"))
    }

    @Test fun escapesSupportedScalarTextWithoutChangingOtherLines() {
        val input = "BEGIN:VCARD\nFN:Alice\nX-UNCHANGED:plain\nEND:VCARD\n"
        val output = prepared(DavFieldPatch.prepare(input,
            DavFieldPatch.Target.CONTACT_NAME, "Alice", "Alice, Jr.; Team\\X"
        ))
        assertTrue(output.patchedText.contains("FN:Alice\\, Jr.\\; Team\\\\X"))
        assertTrue(output.patchedText.contains("X-UNCHANGED:plain"))
    }

    @Test fun refusesAmbiguousAndUnsafeDocumentStructures() {
        val base = "BEGIN:VCARD\nFN:Ada\nEND:VCARD\n"
        val examples = listOf(
            base.replace("FN:Ada", "FN:Ada\nFN:Another"),
            base.replace("FN:Ada", "FN;LANGUAGE=de:Ada"),
            base.replace("FN:Ada", "FN:Ada\n  Lovelace"),
            base + base,
            base.replace("\n", "\r\n").replaceFirst("\r\n", "\n"),
            base.replace("END:VCARD", "END:VEVENT"),
            base.replace("FN:Ada", "FN:Other"),
            base.replace("FN:Ada", "FN:Ada\nBEGIN:VPHOTO\nEND:VPHOTO"),
            "BEGIN:VCARD\nEND:VCARD\n"
        )
        for (example in examples) rejected(DavFieldPatch.prepare(
            example, DavFieldPatch.Target.CONTACT_NAME, "Ada", "New"
        ))
        rejected(DavFieldPatch.prepare(base, DavFieldPatch.Target.CONTACT_NAME,
            "Ada", "Unsafe\nfield"))
        rejected(DavFieldPatch.prepare(base, DavFieldPatch.Target.CONTACT_NAME,
            "Ada", "Ada"))
    }

    @Test fun refusesRecurrenceAndMultipleEventDocuments() {
        val original = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:abc\n" +
            "SUMMARY:Meeting\nRRULE:FREQ=DAILY;COUNT=5\nEND:VEVENT\nEND:VCALENDAR\n"
        rejected(DavFieldPatch.prepare(original, DavFieldPatch.Target.EVENT_TITLE,
            "Meeting", "Updated"))
        val simple = original.replace("RRULE:FREQ=DAILY;COUNT=5\n", "")
        rejected(DavFieldPatch.prepare(simple.replace("END:VCALENDAR",
            "BEGIN:VEVENT\nSUMMARY:Second\nEND:VEVENT\nEND:VCALENDAR"),
            DavFieldPatch.Target.EVENT_TITLE,"Meeting","Updated"))
    }
}

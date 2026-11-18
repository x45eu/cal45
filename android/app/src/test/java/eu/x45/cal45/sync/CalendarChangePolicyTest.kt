package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarChangePolicyTest {
    private val fields = CalendarChangePolicy.Fields(
        "Besprechung", 1795093200000L, 1795096800000L,
        false, "Notizen", "Besprechungsraum"
    )

    @Test fun acceptsOnlyStrongQuotedETags() {
        assertEquals("\"version-2\"", CalDavReader.safeStrongETag("\"version-2\""))
        assertNull(CalDavReader.safeStrongETag("W/\"version-2\""))
        assertNull(CalDavReader.safeStrongETag("version-2"))
        assertNull(CalDavReader.safeStrongETag("\"bad\nheader\""))
        assertNull(CalDavReader.safeStrongETag(null))
    }

    @Test fun updatesOnlyIfLocalFieldsMatchLastImport() {
        val snapshot = CalendarChangePolicy.fingerprint(fields)
        assertTrue(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"new\"", snapshot, fields, false, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"new\"", snapshot, fields.copy(title = "Lokal geändert"),
            false, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"new\"", snapshot, fields, true, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"new\"", snapshot, fields, false, true
        ))
    }

    @Test fun rejectsLegacyUnversionedAndUnchangedEvents() {
        val snapshot = CalendarChangePolicy.fingerprint(fields)
        assertFalse(CalendarChangePolicy.mayUpdate(
            null, "\"new\"", snapshot, fields, false, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"new\"", null, fields, false, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", "\"old\"", snapshot, fields, false, false
        ))
        assertFalse(CalendarChangePolicy.mayUpdate(
            "\"old\"", null, snapshot, fields, false, false
        ))
    }

    @Test fun fingerprintsAreStableAndSensitiveToEveryField() {
        val snapshot = CalendarChangePolicy.fingerprint(fields)
        assertEquals(64, snapshot.length)
        assertEquals(snapshot, CalendarChangePolicy.fingerprint(fields))
        assertFalse(snapshot == CalendarChangePolicy.fingerprint(
            fields.copy(description = "Bearbeitet")
        ))
        assertFalse(snapshot == CalendarChangePolicy.fingerprint(
            fields.copy(start = fields.start + 60_000)
        ))
        assertFalse(snapshot == CalendarChangePolicy.fingerprint(
            fields.copy(location = "Anderer Raum")
        ))
        assertNotNull(snapshot)
    }
}

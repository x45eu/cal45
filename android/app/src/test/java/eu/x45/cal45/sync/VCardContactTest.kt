package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardContactTest {
    @Test fun parsesNamesPhonesEmailsAndFolding() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada Lovelace\r\n" +
            "TEL;TYPE=CELL:+49 123 456\r\nEMAIL;TYPE=WORK:ada@\r\n example.org\r\n" +
            "END:VCARD\r\n"
        val result = VCardContact.parse(card)!!
        assertEquals("Ada Lovelace", result.name)
        assertEquals(listOf("+49 123 456"), result.phones)
        assertEquals(listOf("ada@example.org"), result.emails)
    }

    @Test fun usesStructuredNameFallback() {
        val card = "BEGIN:VCARD\nVERSION:4.0\nN:Lovelace;Ada;;;\nEND:VCARD"
        assertEquals("Ada Lovelace", VCardContact.parse(card)?.name)
    }

    @Test fun ignoresUnsupportedAndInvalidCards() {
        assertNull(VCardContact.parse("not a vcard"))
        assertNull(VCardContact.parse("BEGIN:VCARD\nEND:VCARD"))
        assertNull(VCardContact.parse("BEGIN:VCARD\nFN:\nEND:VCARD"))
        val card = "BEGIN:VCARD\nFN:Alice\nPHOTO;ENCODING=b:ABCD\nEND:VCARD"
        val parsed = VCardContact.parse(card)!!
        assertTrue(parsed.phones.isEmpty())
        assertFalse(parsed.name.isBlank())
    }

    @Test fun deduplicatesRepeatedContactFields() {
        val card = "BEGIN:VCARD\nFN:Alice\nTEL:123\nTEL:123\n" +
            "EMAIL:hello@example.org\nEMAIL:hello@example.org\nEND:VCARD"
        val contact = VCardContact.parse(card)!!
        assertEquals(1, contact.phones.size)
        assertEquals(1, contact.emails.size)
    }
}

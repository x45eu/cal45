package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactChangePolicyTest {
    private val original = VCardContact.Parsed("Ada Lovelace", listOf("111", "222"), listOf("ada@example.org"))

    @Test fun stableFingerprintIndependentOfFieldOrder() {
        val digest = ContactChangePolicy.fingerprint(original)
        assertEquals(64, digest.length)
        assertEquals(digest, ContactChangePolicy.fingerprint(original.copy(phones=listOf("222","111"))))
        assertFalse(digest == ContactChangePolicy.fingerprint(original.copy(name="Ada L.")))
        assertFalse(digest == ContactChangePolicy.fingerprint(original.copy(emails=listOf("other@example.org"))))
    }
    @Test fun localEditsNeverOverwritten() {
        val snapshot = ContactChangePolicy.fingerprint(original)
        val changed = original.copy(name="Ada Byron")
        assertTrue(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original, changed, false, false, false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original.copy(phones=listOf("777","222")), changed, false, false, false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original, changed, true, false, false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original, changed, false, true, false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original, changed, false, false, true))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"", snapshot,
            original, changed.copy(phones=listOf("123")), false, false, false))
    }
    @Test fun legacyAndWeakEtagsRemainProtected() {
        val digest = ContactChangePolicy.fingerprint(original)
        assertFalse(ContactChangePolicy.allowed(null,"\"new\"",digest,original,original,false,false,false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"new\"",null,original,original,false,false,false))
        assertFalse(ContactChangePolicy.allowed("\"old\"","\"old\"",digest,original,original,false,false,false))
        assertFalse(ContactChangePolicy.allowed("W/\"old\"","\"new\"",digest,original,original,false,false,false))
        assertNull(ContactChangePolicy.safeETag("\"bad\nheader\""))
        assertNull(ContactChangePolicy.safeETag("unquoted"))
    }
}

package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncJournalCodecTest {
    private fun row(key: String) = SyncJournalCodec.Entry(
        resourceDigest = key,
        kind = SyncJournalCodec.Kind.EVENT,
        state = SyncJournalCodec.State.LOCAL_CHANGED,
        serverETag = "\"etag-1\"",
        localFingerprint = "f".repeat(64),
        observedAt = 1795050000000L
    )
    @Test fun journalRoundTripsWithoutPlaintextCredentials() {
        val key = SyncJournalCodec.resourceKey(
            "alice@dav.example.org", "https://dav.example.org/alice/contacts/alice.vcf"
        )
        val entries = listOf(row(key))
        val bytes = SyncJournalCodec.encode(entries)
        assertEquals(entries, SyncJournalCodec.decode(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("alice.vcf"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("alice@dav"))
    }
    @Test fun corruptedOrUnknownJournalFailsClosed() {
        val bytes = SyncJournalCodec.encode(listOf(row("a".repeat(64))))
        val damaged = bytes.copyOf()
        damaged[12] = (damaged[12].toInt() xor 0x01).toByte()
        assertThrows(IOException::class.java) { SyncJournalCodec.decode(damaged) }
        assertThrows(IOException::class.java) { SyncJournalCodec.decode(byteArrayOf(1,2,3)) }
        assertThrows(IllegalArgumentException::class.java) {
            SyncJournalCodec.encode(listOf(row("a".repeat(64)), row("a".repeat(64))))
        }
    }
    @Test fun deterministicEncodingAndScopedKeys() {
        val one = row("a".repeat(64))
        val two = row("b".repeat(64))
        assertTrue(SyncJournalCodec.encode(listOf(one,two)).contentEquals(
            SyncJournalCodec.encode(listOf(two,one))
        ))
        assertFalse(SyncJournalCodec.resourceKey("alice", "https://dav.example.org/alice/a.ics") ==
            SyncJournalCodec.resourceKey("bob", "https://dav.example.org/alice/a.ics"))
        assertThrows(IllegalArgumentException::class.java) {
            SyncJournalCodec.resourceKey("alice","http://dav.example.org/a.ics")
        }
    }
}

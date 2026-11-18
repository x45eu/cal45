package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncJournalSummaryTest {
    private fun item(key: Char, kind: SyncJournalCodec.Kind,
                     state: SyncJournalCodec.State, time: Long): SyncJournalCodec.Entry =
        SyncJournalCodec.Entry(key.toString().repeat(64), kind, state, null, null, time)

    @Test fun aggregatesStatesWithoutExposingResourceData() {
        val rows = listOf(
            item('a', SyncJournalCodec.Kind.CONTACT, SyncJournalCodec.State.IMPORTED, 100),
            item('b', SyncJournalCodec.Kind.EVENT, SyncJournalCodec.State.CONFLICT, 200),
            item('c', SyncJournalCodec.Kind.EVENT, SyncJournalCodec.State.BLOCKED, 300),
            item('d', SyncJournalCodec.Kind.CONTACT, SyncJournalCodec.State.LOCAL_CHANGED, 400)
        )
        val result = SyncJournalSummary.summarize(rows)
        assertEquals(4, result.total)
        assertEquals(2, result.contacts)
        assertEquals(2, result.events)
        assertEquals(1, result.imported)
        assertEquals(1, result.conflicts)
        assertEquals(1, result.blocked)
        assertEquals(1, result.localChanged)
        assertEquals(400L, result.latestObservation)
    }

    @Test fun emptyJournalIsSafe() {
        val empty = SyncJournalSummary.summarize(emptyList())
        assertEquals(0, empty.total)
        assertEquals(0L, empty.latestObservation)
    }
}

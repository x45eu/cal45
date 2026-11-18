package eu.x45.cal45.sync

/**
 * Aggregated, privacy-preserving journal statistics for the account page.
 * Never exposes raw resource URLs, event titles, contacts or passwords.
 */
internal object SyncJournalSummary {
    data class Counts(
        val contacts: Int,
        val events: Int,
        val imported: Int,
        val remoteChanged: Int,
        val localChanged: Int,
        val conflicts: Int,
        val blocked: Int,
        val latestObservation: Long
    ) {
        val total: Int get() = contacts + events
    }

    fun summarize(entries: Collection<SyncJournalCodec.Entry>): Counts {
        fun count(state: SyncJournalCodec.State) = entries.count { it.state == state }
        return Counts(
            contacts = entries.count { it.kind == SyncJournalCodec.Kind.CONTACT },
            events = entries.count { it.kind == SyncJournalCodec.Kind.EVENT },
            imported = count(SyncJournalCodec.State.IMPORTED),
            remoteChanged = count(SyncJournalCodec.State.REMOTE_CHANGED),
            localChanged = count(SyncJournalCodec.State.LOCAL_CHANGED),
            conflicts = count(SyncJournalCodec.State.CONFLICT),
            blocked = count(SyncJournalCodec.State.BLOCKED),
            latestObservation = entries.maxOfOrNull { it.observedAt } ?: 0
        )
    }
}

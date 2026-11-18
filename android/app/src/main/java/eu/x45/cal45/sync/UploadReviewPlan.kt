package eu.x45.cal45.sync

/**
 * In-memory, read-only evaluation of one prospective DAV upload.
 *
 * No caller can infer server approval from this: the only positive result is
 * REQUIRES_FRESH_REMOTE_CHECK, never "safe to upload". A separate future
 * interactive workflow must inspect the full remote document, serialize
 * without losing unrecognized data and obtain explicit user approval.
 */
internal object UploadReviewPlan {
    const val MAX_AGE_MS = 5L * 60L * 1000L

    enum class Decision {
        WRONG_RESOURCE,
        WRONG_KIND,
        JOURNAL_UNTRUSTED,
        STALE_OBSERVATION,
        BLOCKED,
        NO_LOCAL_CHANGE,
        UNSUPPORTED_CONTENT,
        REQUIRES_FRESH_REMOTE_CHECK
    }

    data class Local(
        val accountName: String,
        val source: String,
        val kind: SyncJournalCodec.Kind,
        val actualFingerprint: String,
        val deleted: Boolean,
        val recurring: Boolean,
        val hasUnsupportedFields: Boolean,
        val localDirty: Boolean
    )

    fun review(
        journal: SyncJournalCodec.Entry,
        current: Local,
        currentTimeMillis: Long
    ): Decision {
        if (currentTimeMillis < 0 || journal.observedAt > currentTimeMillis ||
            currentTimeMillis - journal.observedAt > MAX_AGE_MS
        ) return Decision.STALE_OBSERVATION
        val resourceKey = try {
            SyncJournalCodec.resourceKey(current.accountName, current.source)
        } catch (_: IllegalArgumentException) {
            return Decision.WRONG_RESOURCE
        }
        if (resourceKey != journal.resourceDigest) return Decision.WRONG_RESOURCE
        if (current.kind != journal.kind) return Decision.WRONG_KIND
        if (journal.state != SyncJournalCodec.State.IMPORTED)
            return Decision.BLOCKED
        val etag = journal.serverETag
        val previous = journal.localFingerprint
        if (ContactChangePolicy.safeETag(etag) == null ||
            previous == null ||
            !previous.matches(Regex("[a-f0-9]{64}")) ||
            !current.actualFingerprint.matches(Regex("[a-f0-9]{64}"))
        ) return Decision.JOURNAL_UNTRUSTED
        if (current.deleted || current.recurring || current.hasUnsupportedFields ||
            !current.localDirty) return Decision.UNSUPPORTED_CONTENT
        if (current.actualFingerprint == previous)
            return Decision.NO_LOCAL_CHANGE
        return Decision.REQUIRES_FRESH_REMOTE_CHECK
    }
}

package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadReviewPlanTest {
    private val account = "alice@dav.example.org"
    private val source = "https://dav.example.org/alice/contacts/one.vcf"
    private val time = 1795050000000L
    private val journal = SyncJournalCodec.Entry(
        resourceDigest = SyncJournalCodec.resourceKey(account, source),
        kind = SyncJournalCodec.Kind.CONTACT,
        state = SyncJournalCodec.State.IMPORTED,
        serverETag = "\"etag-1\"",
        localFingerprint = "a".repeat(64),
        observedAt = time
    )
    private val local = UploadReviewPlan.Local(
        accountName = account,
        source = source,
        kind = SyncJournalCodec.Kind.CONTACT,
        actualFingerprint = "b".repeat(64),
        deleted = false,
        recurring = false,
        hasUnsupportedFields = false,
        localDirty = true
    )
    @Test fun positiveOnlyRequiresFreshRemoteCheck() {
        assertEquals(UploadReviewPlan.Decision.REQUIRES_FRESH_REMOTE_CHECK,
            UploadReviewPlan.review(journal,local,time + 1000))
    }
    @Test fun changedAccountPathOrKindCannotReuseJournal() {
        assertEquals(UploadReviewPlan.Decision.WRONG_RESOURCE,
            UploadReviewPlan.review(journal,local.copy(accountName = "bob"),time))
        assertEquals(UploadReviewPlan.Decision.WRONG_RESOURCE,
            UploadReviewPlan.review(journal,local.copy(source = "https://evil.example/one.vcf"),time))
        assertEquals(UploadReviewPlan.Decision.WRONG_KIND,
            UploadReviewPlan.review(journal,local.copy(kind = SyncJournalCodec.Kind.EVENT),time))
    }
    @Test fun oldOrUntrustedSnapshotsNeverQualify() {
        assertEquals(UploadReviewPlan.Decision.STALE_OBSERVATION,
            UploadReviewPlan.review(journal,local,time + UploadReviewPlan.MAX_AGE_MS + 1))
        assertEquals(UploadReviewPlan.Decision.JOURNAL_UNTRUSTED,
            UploadReviewPlan.review(journal.copy(serverETag = null),local,time))
        assertEquals(UploadReviewPlan.Decision.BLOCKED,
            UploadReviewPlan.review(journal.copy(state = SyncJournalCodec.State.CONFLICT),local,time))
    }
    @Test fun deletionRecurrenceAndUnknownFieldsAreBlocked() {
        for(candidate in listOf(
            local.copy(deleted = true), local.copy(recurring = true),
            local.copy(hasUnsupportedFields = true), local.copy(localDirty = false)
        )) assertEquals(UploadReviewPlan.Decision.UNSUPPORTED_CONTENT,
            UploadReviewPlan.review(journal,candidate,time))
        assertEquals(UploadReviewPlan.Decision.NO_LOCAL_CHANGE,
            UploadReviewPlan.review(journal,local.copy(actualFingerprint = "a".repeat(64)),time))
    }
}

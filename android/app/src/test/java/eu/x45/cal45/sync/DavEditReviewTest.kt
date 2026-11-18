package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavEditReviewTest {
    private val account = "alice@dav.example.org"
    private val source = "https://dav.example.org/alice/addressbook/one.vcf"
    private val now = 1795050000000L
    private val journal = SyncJournalCodec.Entry(
        resourceDigest = SyncJournalCodec.resourceKey(account, source),
        kind = SyncJournalCodec.Kind.CONTACT,
        state = SyncJournalCodec.State.IMPORTED,
        serverETag = "\"rev-1\"",
        localFingerprint = "a".repeat(64),
        observedAt = now
    )
    private val original = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\n" +
        "PHOTO;ENCODING=b:opaque-payload\r\nX-ORIGINAL:preserved\r\nEND:VCARD\r\n"
    private val snapshot = ConditionalDavUpload.Snapshot.Verified("\"rev-1\"",original)

    private fun review(
        state: SyncJournalCodec.Entry = journal,
        remote: ConditionalDavUpload.Snapshot = snapshot,
        user: String = account,
        at: Long = now
    ) = DavEditReview.prepare(
        user,source,state,remote,SyncJournalCodec.Kind.CONTACT,
        DavFieldPatch.Target.CONTACT_NAME,"Ada","Ada Byron",at
    )

    @Test fun preparesExactlyOneLosslessChangeWithoutUpload() {
        val result = review()
        assertTrue(result is DavEditReview.Result.Preview)
        val preview = result as DavEditReview.Result.Preview
        assertEquals(original,preview.preservedOriginal)
        assertEquals(original.replace("FN:Ada","FN:Ada Byron"),preview.preparedDocument)
        assertTrue(preview.preparedDocument.contains("X-ORIGINAL:preserved"))
        assertTrue(preview.preparedDocument.contains("PHOTO;ENCODING=b:opaque-payload"))
    }

    @Test fun staleOrDifferentVersionIsNeverReviewable() {
        for (result in listOf(
            review(user="bob@dav.example.org"),
            review(at=now+UploadReviewPlan.MAX_AGE_MS+1),
            review(state=journal.copy(serverETag="\"rev-2\"")),
            review(state=journal.copy(state=SyncJournalCodec.State.CONFLICT)),
            review(remote=ConditionalDavUpload.Snapshot.Conflict),
            review(remote=ConditionalDavUpload.Snapshot.Unverified),
            review(state=journal.copy(localFingerprint=null))
        )) assertTrue(result is DavEditReview.Result.Rejected)
    }

    @Test fun rejectsChangedServerPropertyEvenWithMatchingETag() {
        val changed = ConditionalDavUpload.Snapshot.Verified(
            "\"rev-1\"",original.replace("FN:Ada","FN:Different")
        )
        assertTrue(review(remote=changed) is DavEditReview.Result.Rejected)
    }
}

package eu.x45.cal45.sync

/**
 * Pure composition of an account-bound journal entry and a freshly verified
 * read-only server snapshot. No networking, Android provider operations,
 * credentials, or approval of DAV writes.
 */
internal object DavEditReview {
    sealed class Result {
        data class Preview(
            val sourceKey: String,
            val property: DavFieldPatch.Target,
            val previousValue: String,
            val proposedValue: String,
            val originalBytes: Int,
            val preparedBytes: Int,
            val preservedOriginal: String,
            val preparedDocument: String
        ) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun prepare(
        accountName: String,
        source: String,
        journal: SyncJournalCodec.Entry,
        snapshot: ConditionalDavUpload.Snapshot,
        kind: SyncJournalCodec.Kind,
        target: DavFieldPatch.Target,
        originalValue: String,
        proposedValue: String,
        observedAt: Long
    ): Result {
        val expectedKey = try {
            SyncJournalCodec.resourceKey(accountName, source)
        } catch (_: IllegalArgumentException) {
            return Result.Rejected("Untrusted source")
        }
        if (expectedKey != journal.resourceDigest || journal.kind != kind)
            return Result.Rejected("Wrong account or object")
        if (journal.state != SyncJournalCodec.State.IMPORTED)
            return Result.Rejected("Unresolved conflict")
        if (observedAt < journal.observedAt ||
            observedAt - journal.observedAt > UploadReviewPlan.MAX_AGE_MS)
            return Result.Rejected("Stale journal observation")
        if (journal.localFingerprint == null)
            return Result.Rejected("No trusted original import")
        val verified = snapshot as? ConditionalDavUpload.Snapshot.Verified
            ?: return Result.Rejected("No verified remote document")
        if (journal.serverETag == null ||
            ContactChangePolicy.safeETag(journal.serverETag) == null ||
            verified.etag != journal.serverETag)
            return Result.Rejected("Server version differs from journal")
        if ((target == DavFieldPatch.Target.CONTACT_NAME &&
                kind != SyncJournalCodec.Kind.CONTACT) ||
            (target == DavFieldPatch.Target.EVENT_TITLE &&
                kind != SyncJournalCodec.Kind.EVENT))
            return Result.Rejected("Wrong media type")
        if (!source.endsWith(if (kind == SyncJournalCodec.Kind.CONTACT) ".vcf" else ".ics",
                ignoreCase = true))
            return Result.Rejected("Wrong resource type")
        val patch = DavFieldPatch.prepare(
            verified.originalText, target, originalValue, proposedValue
        )
        if (patch !is DavFieldPatch.Result.Prepared)
            return Result.Rejected((patch as DavFieldPatch.Result.Rejected).reason)
        // Full original text is carried forward. Unknown properties are never
        // discarded by reducing the document to a parsed data model.
        return Result.Preview(
            sourceKey = expectedKey,
            property = target,
            previousValue = originalValue,
            proposedValue = proposedValue,
            originalBytes = patch.originalText.toByteArray(Charsets.UTF_8).size,
            preparedBytes = patch.patchedText.toByteArray(Charsets.UTF_8).size,
            preservedOriginal = patch.originalText,
            preparedDocument = patch.patchedText
        )
    }
}

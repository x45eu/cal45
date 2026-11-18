package eu.x45.cal45.sync

/**
 * Conservative local-change upload gate. This is deliberately separate from
 * the scheduled SyncAdapters: a user's *explicit* consent is required before
 * any future UI can turn this into an actual network write.
 */
internal object LocalUploadPolicy {
    enum class Reason {
        NOT_APPROVED, NOT_TRACKED, NO_CHANGE, LOCAL_DELETED,
        UNSUPPORTED_FIELDS, RECURRING_EVENT, DIRTY_REMOTE, ALLOWED
    }
    data class Candidate(
        val previousServerETag: String?,
        val previousImportedFingerprint: String?,
        val currentLocalFingerprint: String?,
        val explicitlyApproved: Boolean,
        val locallyDeleted: Boolean,
        val hasUnsupportedFields: Boolean,
        val recurring: Boolean,
        val remoteChangedSinceImport: Boolean
    )
    fun assess(value: Candidate): Reason {
        if (!value.explicitlyApproved) return Reason.NOT_APPROVED
        if (value.locallyDeleted) return Reason.LOCAL_DELETED
        if (value.recurring) return Reason.RECURRING_EVENT
        if (value.hasUnsupportedFields) return Reason.UNSUPPORTED_FIELDS
        if (value.remoteChangedSinceImport) return Reason.DIRTY_REMOTE
        val old = value.previousImportedFingerprint
        val current = value.currentLocalFingerprint
        if (CalDavReader.safeStrongETag(value.previousServerETag) == null ||
            old == null || current == null ||
            !old.matches(Regex("[a-f0-9]{64}")) ||
            !current.matches(Regex("[a-f0-9]{64}")))
            return Reason.NOT_TRACKED
        if (old == current) return Reason.NO_CHANGE
        return Reason.ALLOWED
    }
}

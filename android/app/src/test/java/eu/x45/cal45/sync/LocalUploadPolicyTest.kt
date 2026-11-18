package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalUploadPolicyTest {
    private val c = LocalUploadPolicy.Candidate(
        previousServerETag = "\"version-1\"",
        previousImportedFingerprint = "a".repeat(64),
        currentLocalFingerprint = "b".repeat(64),
        explicitlyApproved = true,
        locallyDeleted = false,
        hasUnsupportedFields = false,
        recurring = false,
        remoteChangedSinceImport = false
    )

    @Test fun onlyApprovedTrackedLocalChangesAreEligible() {
        assertEquals(LocalUploadPolicy.Reason.ALLOWED, LocalUploadPolicy.assess(c))
        assertEquals(LocalUploadPolicy.Reason.NOT_APPROVED,
            LocalUploadPolicy.assess(c.copy(explicitlyApproved = false)))
        assertEquals(LocalUploadPolicy.Reason.NOT_TRACKED,
            LocalUploadPolicy.assess(c.copy(previousServerETag = "W/\"weak\"")))
        assertEquals(LocalUploadPolicy.Reason.NOT_TRACKED,
            LocalUploadPolicy.assess(c.copy(previousImportedFingerprint = null)))
        assertEquals(LocalUploadPolicy.Reason.NO_CHANGE,
            LocalUploadPolicy.assess(c.copy(currentLocalFingerprint = "a".repeat(64))))
    }
    @Test fun refuseDeletionsRecurrencesUnsupportedFieldsAndRemoteConflicts() {
        assertEquals(LocalUploadPolicy.Reason.LOCAL_DELETED,
            LocalUploadPolicy.assess(c.copy(locallyDeleted = true)))
        assertEquals(LocalUploadPolicy.Reason.RECURRING_EVENT,
            LocalUploadPolicy.assess(c.copy(recurring = true)))
        assertEquals(LocalUploadPolicy.Reason.UNSUPPORTED_FIELDS,
            LocalUploadPolicy.assess(c.copy(hasUnsupportedFields = true)))
        assertEquals(LocalUploadPolicy.Reason.DIRTY_REMOTE,
            LocalUploadPolicy.assess(c.copy(remoteChangedSinceImport = true)))
    }
}

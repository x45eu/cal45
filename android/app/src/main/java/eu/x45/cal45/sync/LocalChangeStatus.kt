package eu.x45.cal45.sync

/**
 * Pure, testable status classification for a read-only local change preview.
 * There is intentionally no "approved" outcome in this preview.
 */
internal object LocalChangeStatus {
    fun calendar(
        previousFingerprint: String?,
        currentFingerprint: String,
        locallyDeleted: Boolean,
        recurring: Boolean
    ): String = when {
        locallyDeleted -> "Lokal gelöscht – Upload gesperrt"
        recurring -> "Terminserie – Upload gesperrt"
        previousFingerprint.isNullOrEmpty() -> "Älterer Import – Upload gesperrt"
        previousFingerprint == currentFingerprint -> "Kein Unterschied zu importierten Feldern"
        else -> "Lokal verändert – Upload noch deaktiviert"
    }

    fun contact(
        tracked: Boolean,
        currentChanged: Boolean,
        unsupportedLocalFields: Boolean,
        invalidFieldCounts: Boolean,
        locallyDeleted: Boolean
    ): String = when {
        locallyDeleted -> "Lokal gelöscht – Upload gesperrt"
        !tracked -> "Älterer Import – Upload gesperrt"
        unsupportedLocalFields || invalidFieldCounts -> "Zusätzliche Felder – Upload gesperrt"
        currentChanged -> "Lokal verändert – Upload noch deaktiviert"
        else -> "Kein Unterschied zu importierten Feldern"
    }
}

package eu.x45.cal45.sync

import java.security.MessageDigest

/**
 * Immutable import snapshot. It contains no private event text, only a digest.
 * A local modification invalidates the snapshot and blocks a remote overwrite.
 */
internal object CalendarChangePolicy {
    data class Fields(
        val title: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val description: String,
        val location: String
    )
    fun from(parsed: ICalendarEvent.Parsed): Fields = Fields(
        parsed.title, parsed.startMillis, parsed.endMillis, parsed.allDay,
        parsed.description, parsed.location
    )
    fun fingerprint(fields: Fields): String {
        val pieces = listOf(
            fields.title, fields.start.toString(), fields.end.toString(),
            if (fields.allDay) "1" else "0", fields.description, fields.location
        )
        val normalized = buildString {
            for (piece in pieces) append(piece.length).append(':').append(piece)
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun mayUpdate(
        previousETag: String?,
        remoteETag: String?,
        previousFingerprint: String?,
        local: Fields,
        dirty: Boolean,
        deleted: Boolean
    ): Boolean =
        !dirty && !deleted &&
        previousETag != null && remoteETag != null &&
        CalDavReader.safeStrongETag(previousETag) != null &&
        CalDavReader.safeStrongETag(remoteETag) != null &&
        previousETag != remoteETag &&
        previousFingerprint != null &&
        previousFingerprint == fingerprint(local)
}

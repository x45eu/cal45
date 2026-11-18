package eu.x45.cal45.sync

import java.security.MessageDigest

/**
 * Safe subset for CardDAV updates. The stored snapshot is only a digest;
 * older imports without a snapshot can never be updated automatically.
 */
internal object ContactChangePolicy {
    fun fingerprint(card: VCardContact.Parsed): String {
        val fields = listOf(card.name) + card.phones.sorted() + card.emails.sorted()
        val encoded = buildString {
            append("cal45-v1:")
            append(card.phones.size).append(':').append(card.emails.size).append(':')
            for (value in fields) append(value.length).append(':').append(value)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun allowed(
        oldETag: String?, newETag: String?, savedFingerprint: String?,
        local: VCardContact.Parsed, remote: VCardContact.Parsed,
        dirty: Boolean, deleted: Boolean, unsupportedLocalFields: Boolean
    ): Boolean =
        !dirty && !deleted && !unsupportedLocalFields &&
        safeETag(oldETag) != null && safeETag(newETag) != null &&
        oldETag != newETag && savedFingerprint != null &&
        savedFingerprint == fingerprint(local) &&
        local.phones.size == remote.phones.size &&
        local.emails.size == remote.emails.size

    // ETags in HTTP If-Match need strong quoting; weak and control characters
    // are not sent as conditional request headers.
    fun safeETag(tag: String?): String? {
        if (tag == null || tag.length !in 3..182 || !tag.startsWith('"') ||
            !tag.endsWith('"')) return null
        val body = tag.substring(1, tag.length - 1)
        if (body.any { it == '"' || it.code < 32 || it.code == 127 }) return null
        return tag
    }
}

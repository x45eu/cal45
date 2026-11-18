package eu.x45.cal45.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Versioned, bounded binary journal format. Does not contain event/contact
 * contents, plaintext credentials, account names or resource URLs.
 *
 * The journal is diagnostic only: merely having a journal row NEVER approves
 * a DAV write. Any malformed journal is rejected, not silently trusted.
 */
internal object SyncJournalCodec {
    const val VERSION = 1
    const val MAX_ENTRIES = 4000
    private const val MAX_BLOB = 1024 * 1024
    private const val MAGIC = 0x43413435 // CA45

    enum class Kind { CONTACT, EVENT }
    enum class State { IMPORTED, REMOTE_CHANGED, LOCAL_CHANGED, CONFLICT, BLOCKED }

    data class Entry(
        val resourceDigest: String,
        val kind: Kind,
        val state: State,
        val serverETag: String?,
        val localFingerprint: String?,
        val observedAt: Long
    ) {
        init {
            require(resourceDigest.matches(Regex("[a-f0-9]{64}")))
            require(serverETag == null || ContactChangePolicy.safeETag(serverETag) != null)
            require(localFingerprint == null ||
                localFingerprint.matches(Regex("[a-f0-9]{64}")))
            require(observedAt >= 0)
        }
    }

    fun resourceKey(account: String, source: String): String {
        require(account.isNotBlank() && source.startsWith("https://"))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((account.length.toString() + ":" + account + source)
                .toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encode(entries: Collection<Entry>): ByteArray {
        require(entries.size <= MAX_ENTRIES)
        val unique = HashSet<String>()
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(entries.size)
            for (entry in entries.sortedBy { it.resourceDigest }) {
                require(unique.add(entry.resourceDigest))
                out.writeUTF(entry.resourceDigest)
                out.writeByte(entry.kind.ordinal)
                out.writeByte(entry.state.ordinal)
                out.writeBoolean(entry.serverETag != null)
                if (entry.serverETag != null) out.writeUTF(entry.serverETag)
                out.writeBoolean(entry.localFingerprint != null)
                if (entry.localFingerprint != null) out.writeUTF(entry.localFingerprint)
                out.writeLong(entry.observedAt)
            }
        }
        val bytes = payload.toByteArray()
        require(bytes.size <= MAX_BLOB)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return bytes + digest
    }

    fun decode(bytes: ByteArray): List<Entry> {
        if (bytes.size < 44 || bytes.size > MAX_BLOB + 32) throw IOException("Journalgröße ungültig")
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        val checksum = bytes.copyOfRange(bytes.size - 32, bytes.size)
        if (!MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(payload), checksum
            )) throw IOException("Journal-Prüfsumme ungültig")
        return try {
            val items = ArrayList<Entry>()
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION)
                    throw IOException("Journal-Version unbekannt")
                val count = input.readInt()
                if (count !in 0..MAX_ENTRIES) throw IOException("Zu viele Einträge")
                val seen = HashSet<String>()
                repeat(count) {
                    val key = input.readUTF()
                    val kindIndex = input.readUnsignedByte()
                    val stateIndex = input.readUnsignedByte()
                    if (kindIndex !in Kind.entries.indices ||
                        stateIndex !in State.entries.indices ||
                        !seen.add(key)) throw IOException("Ungültiger Journaleintrag")
                    val etag = if (input.readBoolean()) input.readUTF() else null
                    val fingerprint = if (input.readBoolean()) input.readUTF() else null
                    val observed = input.readLong()
                    items.add(Entry(key, Kind.entries[kindIndex],
                        State.entries[stateIndex], etag, fingerprint, observed))
                }
                if (input.available() != 0) throw IOException("Journal mit zusätzlichen Daten")
            }
            items
        } catch (e: IllegalArgumentException) {
            throw IOException("Ungültige Journaldaten", e)
        }
    }
}

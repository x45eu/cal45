package eu.x45.cal45.sync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android-private atomic journal. Account-scoped filename and resource keys
 * are SHA-256 hashes; there are no resource URLs or contact/event contents.
 * Android app backups are disabled in this project.
 *
 * Lock serializes in-process SyncAdapter and UI access. Multi-process access
 * is intentionally unsupported; Cal45 services run in the app process.
 */
internal class SyncJournalStore(
    context: Context,
    private val accountName: String
) {
    private val directory = File(context.filesDir, "sync-journal")
    private val file = AtomicFile(File(
        directory,
        SyncJournalCodec.resourceKey("journal", "https://journal.invalid/" +
            accountName.toByteArray(Charsets.UTF_8).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }) + ".dat"
    ))
    companion object {
        private val lock = Any()
    }

    fun list(): List<SyncJournalCodec.Entry> = synchronized(lock) { readLocked() }

    private fun readLocked(): List<SyncJournalCodec.Entry> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            SyncJournalCodec.decode(bytes)
        } catch (e: FileNotFoundException) {
            throw IOException("Journaldaten nicht verfügbar", e)
        }
    }

    fun observe(
        source: String, kind: SyncJournalCodec.Kind, state: SyncJournalCodec.State,
        serverETag: String?, localFingerprint: String?
    ) = synchronized(lock) {
        val records = readLocked().associateBy { it.resourceDigest }.toMutableMap()
        val key = SyncJournalCodec.resourceKey(accountName, source)
        // Missing/weak ETags must not become a trusted version in journal.
        val entry = SyncJournalCodec.Entry(
            key, kind, state, ContactChangePolicy.safeETag(serverETag),
            localFingerprint, System.currentTimeMillis()
        )
        records[key] = entry
        if (records.size > SyncJournalCodec.MAX_ENTRIES)
            throw IOException("Journal-Sicherheitslimit erreicht")
        val bytes = SyncJournalCodec.encode(records.values)
        if (!directory.exists() && !directory.mkdirs())
            throw IOException("Journalordner nicht verfügbar")
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw IOException("Journaldaten konnten nicht sicher gespeichert werden", e)
        }
    }

    fun discard() = synchronized(lock) {
        // Only called when the user removes the matching Cal45 account.
        file.delete()
    }
}

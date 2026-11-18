package eu.x45.cal45.sync

import android.Manifest
import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract

/**
 * Read-only and import-only CardDAV to Android ContactsProvider.
 * Adds missing server cards. Existing supported contact fields are only
 * updated if the previous import fingerprint still matches and the raw
 * contact is clean. Never uploads or deletes records.
 */
internal class ContactsSyncAdapter(context: Context) :
    AbstractThreadedSyncAdapter(context, true, false) {

    private fun report(message: String) {
        context.getSharedPreferences("cal45_contact_sync_status", Context.MODE_PRIVATE)
            .edit().putString("message", message)
            .putLong("timestamp", System.currentTimeMillis()).apply()
    }

    override fun onPerformSync(
        account: Account, extras: Bundle, authority: String,
        provider: ContentProviderClient, syncResult: SyncResult
    ) {
        if (authority != ContactsContract.AUTHORITY || account.type != Cal45SystemAccount.TYPE) return
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            syncResult.stats.numAuthExceptions++
            report("Kontakte-Berechtigung fehlt oder wurde entzogen.")
            return
        }
        val vault = CredentialVault(context)
        val server = vault.server()
        val user = vault.username()
        val password = vault.readAppPassword()
        if (!vault.configured() || password == null || !DavProbe.looksLikeAppPassword(password) ||
            DavProbe.normalizedUrl(server) == null ||
            Cal45SystemAccount.accountName(server, user) != account.name) {
            syncResult.stats.numAuthExceptions++
            report("Cal45-Systemkonto oder gespeichertes App-Passwort ungültig.")
            return
        }
        try {
            val existing = HashMap<String, ImportedContact>()
            val uri = ContactsContract.RawContacts.CONTENT_URI
            context.contentResolver.query(
                uri, arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.SOURCE_ID,
                    ContactsContract.RawContacts.SYNC1,
                    ContactsContract.RawContacts.SYNC2,
                    ContactsContract.RawContacts.DIRTY,
                    ContactsContract.RawContacts.DELETED
                ),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?",
                arrayOf(account.type, account.name), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val source = cursor.getString(1) ?: continue
                    existing[source] = ImportedContact(
                        cursor.getLong(0), cursor.getString(2), cursor.getString(3),
                        cursor.getInt(4) != 0, cursor.getInt(5) != 0
                    )
                }
            } ?: throw IllegalStateException("Kontakteanbieter nicht verfügbar")
            val reader = CardDavReader(server, user, password)
            val scan = reader.scanCards(existing.mapValues { it.value.etag })
            var imported = 0
            var updated = 0
            var protected = scan.protectedChanges
            var skipped = 0
            for (card in scan.newCards) {
                if (Thread.currentThread().isInterrupted) break
                val parsed = VCardContact.parse(card.text)
                if (parsed == null) { skipped++; continue }
                addContact(account, card.source, card.etag, parsed)
                imported++
                syncResult.stats.numInserts++
            }
            for (card in scan.changedCards) {
                if (Thread.currentThread().isInterrupted) break
                val prior = existing[card.source]
                val remote = VCardContact.parse(card.text)
                if (prior == null || remote == null) {
                    protected++
                    continue
                }
                if (updateContact(account, card.source, card.etag, prior, remote)) {
                    updated++
                    syncResult.stats.numUpdates++
                } else protected++
            }
            report("Kontaktabgleich: $imported neu, $updated sicher aktualisiert, " +
                "$protected geschützt (lokale Änderung/Altbestand), " +
                "$skipped nicht unterstützte Karten.")
        } catch (_: java.io.IOException) {
            syncResult.stats.numIoExceptions++
            report("DAV-Verbindung oder Adressbuchabruf fehlgeschlagen.")
        } catch (_: android.os.RemoteException) {
            syncResult.stats.numIoExceptions++
            report("Android-Kontakteanbieter konnte nicht aktualisiert werden.")
        } catch (_: Exception) {
            syncResult.stats.numParseExceptions++
            report("Kontakteimport fehlgeschlagen: Datenformat oder lokale Verarbeitung.")
        }
    }

    private data class ImportedContact(
        val id: Long,
        val etag: String?,
        val fingerprint: String?,
        val dirty: Boolean,
        val deleted: Boolean
    )
    private data class DataField(val id: Long, val value: String)

    // Only the subset represented by our conservative vCard parser can be
    // refreshed. Extra contact fields imply a potentially richer local edit.
    private fun updateContact(
        account: Account,
        source: String,
        etag: String?,
        prior: ImportedContact,
        remote: VCardContact.Parsed
    ): Boolean {
        val data = ContactsContract.Data
        val names = ArrayList<DataField>()
        val phones = ArrayList<DataField>()
        val emails = ArrayList<DataField>()
        var unsupported = false
        context.contentResolver.query(
            data.CONTENT_URI, arrayOf(data._ID, data.MIMETYPE, data.DATA1),
            "${data.RAW_CONTACT_ID}=?", arrayOf(prior.id.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val field = DataField(cursor.getLong(0), cursor.getString(2) ?: "")
                when (cursor.getString(1)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> names.add(field)
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.add(field)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.add(field)
                    else -> unsupported = true
                }
                if (names.size > 1 || phones.size > 10 || emails.size > 10) unsupported = true
            }
        } ?: return false
        if (names.size != 1) return false
        val current = VCardContact.Parsed(
            names.single().value, phones.map { it.value }, emails.map { it.value }
        )
        if (!ContactChangePolicy.allowed(
                prior.etag, etag, prior.fingerprint, current, remote,
                prior.dirty, prior.deleted, unsupported
            )) return false

        // Atomic transaction: re-check the exact raw-contact metadata and each
        // prior field value. Concurrent modifications fail rather than overwrite.
        val raw = ContactsContract.RawContacts
        val rawUri = raw.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val dataUri = data.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val where = "${raw._ID}=? AND ${raw.ACCOUNT_TYPE}=? AND ${raw.ACCOUNT_NAME}=? AND " +
            "${raw.SOURCE_ID}=? AND ${raw.SYNC1}=? AND ${raw.SYNC2}=? AND " +
            "${raw.DIRTY}=0 AND ${raw.DELETED}=0"
        val params = arrayOf(
            prior.id.toString(), account.type, account.name, source,
            prior.etag ?: return false, prior.fingerprint ?: return false
        )
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newAssertQuery(rawUri)
            .withSelection(where, params).withExpectedCount(1).build())
        fun changes(old: List<DataField>, fresh: List<String>) {
            val sortedOld = old.sortedWith(compareBy<DataField> { it.value }.thenBy { it.id })
            val sortedNew = fresh.sorted()
            for (index in sortedOld.indices) {
                if (sortedOld[index].value == sortedNew[index]) continue
                ops.add(ContentProviderOperation.newUpdate(dataUri)
                    .withSelection(
                        "${data._ID}=? AND ${data.RAW_CONTACT_ID}=? AND ${data.DATA1}=?",
                        arrayOf(
                            sortedOld[index].id.toString(), prior.id.toString(),
                            sortedOld[index].value
                        )
                    )
                    .withValue(data.DATA1, sortedNew[index])
                    .withExpectedCount(1).build())
            }
        }
        changes(names, listOf(remote.name))
        changes(phones, remote.phones)
        changes(emails, remote.emails)
        ops.add(ContentProviderOperation.newUpdate(rawUri)
            .withSelection(where, params)
            .withValue(raw.SYNC1, etag)
            .withValue(raw.SYNC2, ContactChangePolicy.fingerprint(remote))
            .withExpectedCount(1).build())
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (_: android.content.OperationApplicationException) {
            false // Conflicting local changes, no partial writes.
        }
    }

    private fun addContact(account: Account, source: String, etag: String?, card: VCardContact.Parsed) {
        val rawUri = ContactsContract.RawContacts.CONTENT_URI
            .buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val dataUri = ContactsContract.Data.CONTENT_URI
            .buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val operations = ArrayList<ContentProviderOperation>()
        operations.add(ContentProviderOperation.newInsert(rawUri)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, source)
            .withValue(ContactsContract.RawContacts.SYNC1, etag)
            .withValue(ContactsContract.RawContacts.SYNC2, ContactChangePolicy.fingerprint(card))
            .build())
        fun field(mime: String, column: String, value: String) {
            operations.add(ContentProviderOperation.newInsert(dataUri)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
                .withValue(column, value)
                .build())
        }
        field(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, card.name)
        for (phone in card.phones) {
            field(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
        }
        for (email in card.emails) {
            field(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS, email)
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }
}

class ContactsSyncService : android.app.Service() {
    private val adapter by lazy { ContactsSyncAdapter(this) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

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
 * Adds missing server cards only; existing contacts, local modifications,
 * deleted remote cards and unsupported vCard fields are NEVER overwritten.
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
            val existing = HashSet<String>()
            val uri = ContactsContract.RawContacts.CONTENT_URI
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.RawContacts.SOURCE_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?",
                arrayOf(account.type, account.name), null
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { existing.add(it) }
            } ?: throw IllegalStateException("Kontakteanbieter nicht verfügbar")
            val reader = CardDavReader(server, user, password)
            val cards = reader.fetchNewCards(existing)
            var imported = 0
            var skipped = 0
            for (card in cards) {
                if (Thread.currentThread().isInterrupted) break
                val parsed = VCardContact.parse(card.text)
                if (parsed == null) { skipped++; continue }
                addContact(account, card.source, parsed)
                imported++
                syncResult.stats.numInserts++
            }
            report("Import beendet: $imported neue Kontakte, $skipped nicht unterstützte Karten übersprungen.")
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

    private fun addContact(account: Account, source: String, card: VCardContact.Parsed) {
        val rawUri = ContactsContract.RawContacts.CONTENT_URI
            .buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val dataUri = ContactsContract.Data.CONTENT_URI
            .buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val operations = ArrayList<ContentProviderOperation>()
        operations.add(ContentProviderOperation.newInsert(rawUri)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, source)
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

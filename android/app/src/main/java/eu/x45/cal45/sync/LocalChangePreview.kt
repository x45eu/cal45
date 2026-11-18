package eu.x45.cal45.sync

import android.Manifest
import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract

/**
 * Read-only, bounded inspection of changes to Cal45-owned Android records.
 * No server access, no token access, no writes and no approval state stored.
 *
 * A "candidate" is NOT permission to upload: it still requires a complete
 * serializer, fresh server ETag verification and explicit user approval.
 */
internal class LocalChangePreview(private val context: Context) {
    data class Item(
        val type: String,
        val name: String,
        val status: String,
        val eligibleForFutureReview: Boolean
    )

    fun inspect(account: Account, limit: Int = 100): List<Item> {
        require(account.type == Cal45SystemAccount.TYPE && limit in 1..100)
        val result = ArrayList<Item>()
        inspectEvents(account, result, limit)
        if (result.size < limit) inspectContacts(account, result, limit)
        return result
    }

    private fun inspectEvents(account: Account, items: MutableList<Item>, limit: Int) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED) {
            items.add(Item("Kalender", "Berechtigung fehlt", "GESPERRT", false))
            return
        }
        val cols = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.SYNC_DATA2,
            CalendarContract.Events.SYNC_DATA3,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.RRULE
        )
        val query = CalendarContract.Events.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            .build()
        // Query via account-scoped calendar IDs; do not rely on unsupported
        // cross-table subqueries in Android calendar providers.
        val calendarIds = ArrayList<Long>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (calendarIds.size >= 100) break
                calendarIds.add(cursor.getLong(0))
            }
        } ?: return
        for (calendarId in calendarIds) {
            if (items.size >= limit) break
            context.contentResolver.query(
                query, cols,
                "${CalendarContract.Events.CALENDAR_ID}=? AND " +
                    "(${CalendarContract.Events.DIRTY}!=0 OR " +
                    "${CalendarContract.Events.DELETED}!=0)",
                arrayOf(calendarId.toString()), null
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < limit) {
                    val source = cursor.getString(1)
                    if (source.isNullOrBlank()) continue
                    val snapshot = cursor.getString(3)
                    val local = CalendarChangePolicy.Fields(
                        cursor.getString(0) ?: "",
                        cursor.getLong(4), cursor.getLong(5),
                        cursor.getInt(6) != 0,
                        cursor.getString(7) ?: "", cursor.getString(8) ?: ""
                    )
                    val deleted = cursor.getInt(10) != 0
                    val series = !cursor.getString(11).isNullOrBlank()
                    // Preview never approves uploads. The specific type of
                    // blocked change is shown to the user instead.
                    val status = LocalChangeStatus.calendar(
                        snapshot, CalendarChangePolicy.fingerprint(local), deleted, series
                    )
                    items.add(Item("Termin", cursor.getString(0) ?: "(ohne Titel)", status, false))
                }
            }
        }
    }

    private fun inspectContacts(account: Account, items: MutableList<Item>, limit: Int) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) {
            if (items.size < limit) items.add(Item("Kontakte", "Berechtigung fehlt", "GESPERRT", false))
            return
        }
        val columns = arrayOf(
            ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.SYNC2,
            ContactsContract.RawContacts.DIRTY, ContactsContract.RawContacts.DELETED
        )
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, columns,
            "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                "(${ContactsContract.RawContacts.DIRTY}!=0 OR ${ContactsContract.RawContacts.DELETED}!=0)",
            arrayOf(account.name, account.type), null
        )?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) {
                if (cursor.getString(1).isNullOrBlank()) continue
                val id = cursor.getLong(0)
                val deleted = cursor.getInt(5) != 0
                if (deleted) {
                    items.add(Item("Kontakt", "Kontakt #$id",
                        "Lokal gelöscht – Upload gesperrt", false))
                    continue
                }
                val names = ArrayList<String>()
                val phones = ArrayList<String>()
                val emails = ArrayList<String>()
                var extra = false
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                    "${ContactsContract.Data.RAW_CONTACT_ID}=?",
                    arrayOf(id.toString()), null
                )?.use { data ->
                    while (data.moveToNext()) {
                        when (data.getString(0)) {
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                                names.add(data.getString(1) ?: "")
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                                phones.add(data.getString(1) ?: "")
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                                emails.add(data.getString(1) ?: "")
                            else -> extra = true
                        }
                    }
                } ?: continue
                val parsed = VCardContact.Parsed(
                    names.firstOrNull() ?: "", phones, emails
                )
                val tracked = ContactChangePolicy.safeETag(cursor.getString(2)) != null &&
                    cursor.getString(3)?.matches(Regex("[a-f0-9]{64}")) == true
                val changed = tracked && cursor.getString(3) !=
                    ContactChangePolicy.fingerprint(parsed)
                val status = LocalChangeStatus.contact(
                    tracked, changed, extra,
                    names.size != 1 || phones.size > 10 || emails.size > 10, false
                )
                items.add(Item("Kontakt", parsed.name.ifEmpty { "Kontakt #$id" }, status, false))
            }
        }
    }
}

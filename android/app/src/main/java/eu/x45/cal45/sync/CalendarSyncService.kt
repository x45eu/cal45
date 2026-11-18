package eu.x45.cal45.sync

import android.Manifest
import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract

/**
 * Remote-to-local import of simple events and finite UTC/all-day series.
 * Updates only clean, fingerprint-matching imported events with strong ETags.
 * Never uploads or deletes events; legacy and locally edited entries stay untouched.
 */
internal class CalendarSyncAdapter(context: Context) :
    AbstractThreadedSyncAdapter(context, true, false) {

    private fun report(message: String) {
        context.getSharedPreferences("cal45_calendar_sync_status", Context.MODE_PRIVATE)
            .edit().putString("message", message)
            .putLong("timestamp", System.currentTimeMillis()).apply()
    }

    override fun onPerformSync(
        account: Account, extras: Bundle, authority: String,
        provider: ContentProviderClient, syncResult: SyncResult
    ) {
        if (authority != CalendarContract.AUTHORITY || account.type != Cal45SystemAccount.TYPE) return
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            syncResult.stats.numAuthExceptions++
            report("Kalender-Berechtigung fehlt.")
            return
        }
        val vault = CredentialVault(context)
        val server = vault.server()
        val username = vault.username()
        val password = vault.readAppPassword()
        if (!vault.configured() || password == null || !DavProbe.looksLikeAppPassword(password) ||
            DavProbe.normalizedUrl(server) == null ||
            Cal45SystemAccount.accountName(server, username) != account.name) {
            syncResult.stats.numAuthExceptions++
            report("Cal45-Konto oder App-Passwort nicht gültig.")
            return
        }
        try {
            val allCalendars = HashMap<String, Long>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_LOCATION),
                "${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.ACCOUNT_NAME}=?",
                arrayOf(account.type, account.name), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val location = cursor.getString(1)
                    if (!location.isNullOrBlank()) allCalendars[location] = cursor.getLong(0)
                }
            } ?: throw IllegalStateException("Android-Kalenderanbieter nicht erreichbar")

            data class Imported(
                val id: Long,
                val etag: String?,
                val fingerprint: String?,
                val fields: CalendarChangePolicy.Fields,
                val dirty: Boolean,
                val deleted: Boolean,
                val recurring: Boolean
            )
            // Legacy imports have no ETag/snapshot and cannot be overwritten.
            val known = HashMap<String, Imported>()
            val cols = arrayOf(
                CalendarContract.Events._ID, CalendarContract.Events.SYNC_DATA1,
                CalendarContract.Events.SYNC_DATA2, CalendarContract.Events.SYNC_DATA3,
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED,
                CalendarContract.Events.RRULE
            )
            for (calendarId in allCalendars.values) {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI, cols,
                    "${CalendarContract.Events.CALENDAR_ID}=?",
                    arrayOf(calendarId.toString()), null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val source = cursor.getString(1) ?: continue
                        known[source] = Imported(
                            cursor.getLong(0), cursor.getString(2), cursor.getString(3),
                            CalendarChangePolicy.Fields(
                                cursor.getString(4) ?: "",
                                cursor.getLong(5), cursor.getLong(6),
                                cursor.getInt(7) == 1,
                                cursor.getString(8) ?: "", cursor.getString(9) ?: ""
                            ),
                            cursor.getInt(10) != 0, cursor.getInt(11) != 0,
                            !cursor.getString(12).isNullOrBlank()
                        )
                    }
                } ?: throw IllegalStateException("Android-Termine nicht lesbar")
            }
            val remoteEvents = CalDavReader(server, username, password)
                .fetchChangedEvents(known.mapValues { it.value.etag })
            var imported = 0
            var updated = 0
            var conflicts = 0
            var skipped = 0
            for (remote in remoteEvents) {
                if (Thread.currentThread().isInterrupted) break
                val event = ICalendarEvent.parse(remote.text)
                if (event == null) { skipped++;continue }
                val existing = known[remote.source]
                // Recurrence changes and conversions to/from recurrence require
                // exception-aware reconciliation. Never update them implicitly.
                if (existing != null && (existing.recurring || event.rrule != null)) {
                    conflicts++
                    continue
                }
                if (existing != null && !CalendarChangePolicy.mayUpdate(
                        existing.etag, remote.etag, existing.fingerprint, existing.fields,
                        existing.dirty, existing.deleted
                    )) {
                    conflicts++
                    continue
                }
                val calendarId = if (existing == null) {
                    allCalendars[remote.calendarUrl]
                        ?: createCalendar(account, remote.calendarUrl).also {
                            allCalendars[remote.calendarUrl] = it
                        }
                } else 0L
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DTSTART, event.startMillis)
                    if (event.rrule == null) {
                        put(CalendarContract.Events.DTEND, event.endMillis)
                    } else {
                        put(CalendarContract.Events.RRULE, event.rrule)
                        put(CalendarContract.Events.DURATION,
                            SafeRecurrence.androidDuration(
                                event.endMillis - event.startMillis, event.allDay
                            ))
                    }
                    put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DESCRIPTION, event.description)
                    put(CalendarContract.Events.EVENT_LOCATION, event.location)
                    put(CalendarContract.Events.SYNC_DATA1, remote.source)
                    put(CalendarContract.Events.SYNC_DATA2, remote.etag)
                    put(CalendarContract.Events.SYNC_DATA3,
                        CalendarChangePolicy.fingerprint(CalendarChangePolicy.from(event)))
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                }
                if (existing == null) {
                    val result = context.contentResolver.insert(
                        syncUri(CalendarContract.Events.CONTENT_URI, account), values
                    )
                    if (result == null) throw IllegalStateException("Termin konnte nicht gespeichert werden")
                    imported++
                    syncResult.stats.numInserts++
                } else {
                    // The provider verifies source, previous ETag and not-dirty
                    // state again at update time, rejecting concurrent changes.
                    values.remove(CalendarContract.Events.CALENDAR_ID)
                    values.remove(CalendarContract.Events.STATUS)
                    val count = context.contentResolver.update(
                        syncUri(CalendarContract.Events.CONTENT_URI, account),
                        values,
                        "${CalendarContract.Events._ID}=? AND " +
                            "${CalendarContract.Events.SYNC_DATA1}=? AND " +
                            "${CalendarContract.Events.SYNC_DATA2}=? AND " +
                            "${CalendarContract.Events.DIRTY}=0 AND " +
                            "${CalendarContract.Events.DELETED}=0",
                        arrayOf(
                            existing.id.toString(), remote.source,
                            existing.etag ?: ""
                        )
                    )
                    if (count == 1) {
                        updated++
                        syncResult.stats.numUpdates++
                    } else conflicts++
                }
            }
            report("Kalenderabgleich: $imported neu, $updated aktualisiert, " +
                "$conflicts lokale Konflikte/Altbestände geschützt, " +
                "$skipped nicht unterstützte Termine.")
        } catch (_: java.io.IOException) {
            syncResult.stats.numIoExceptions++
            report("CalDAV-Verbindung oder Kalenderabruf fehlgeschlagen.")
        } catch (_: android.os.RemoteException) {
            syncResult.stats.numIoExceptions++
            report("Android-Kalenderanbieter nicht erreichbar.")
        } catch (_: Exception) {
            syncResult.stats.numParseExceptions++
            report("Kalenderimport fehlgeschlagen: Daten oder Verarbeitung.")
        }
    }

    private fun syncUri(base: android.net.Uri, account: Account): android.net.Uri =
        base.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            .build()

    private fun createCalendar(account: Account, location: String): Long {
        val title = location.trimEnd('/').substringAfterLast('/').take(80).ifEmpty { "Cal45" }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            put(CalendarContract.Calendars.NAME, "Cal45: $title")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Cal45 · $title")
            put(CalendarContract.Calendars.CALENDAR_LOCATION, location)
            put(CalendarContract.Calendars.CALENDAR_COLOR, android.graphics.Color.rgb(96, 165, 250))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val inserted = context.contentResolver.insert(syncUri(CalendarContract.Calendars.CONTENT_URI, account), values)
            ?: throw IllegalStateException("Cal45-Kalender konnte nicht erstellt werden")
        return inserted.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("Neue Kalender-ID fehlt")
    }
}

class CalendarSyncService : android.app.Service() {
    private val adapter by lazy { CalendarSyncAdapter(this) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

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
 * Manual, remote-to-local import of standalone CalDAV events.
 * Never uploads, updates or deletes events, even when remote data changes.
 */
internal class CalendarSyncAdapter(context: Context) :
    AbstractThreadedSyncAdapter(context, true, false) {

    private val calendars = CalendarContract.Calendars
    private val events = CalendarContract.Events

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
                calendars.CONTENT_URI,
                arrayOf(calendars._ID, calendars.CALENDAR_LOCATION),
                "${calendars.ACCOUNT_TYPE}=? AND ${calendars.ACCOUNT_NAME}=?",
                arrayOf(account.type, account.name), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val location = cursor.getString(1)
                    if (!location.isNullOrBlank()) allCalendars[location] = cursor.getLong(0)
                }
            } ?: throw IllegalStateException("Android-Kalenderanbieter nicht erreichbar")

            // Source IDs are full HTTPS resource URLs; previously imported
            // events stay untouched even if a server-side event changes.
            val known = HashSet<String>()
            for (calendarId in allCalendars.values) {
                context.contentResolver.query(
                    events.CONTENT_URI,
                    arrayOf(events.SYNC_DATA1),
                    "${events.CALENDAR_ID}=?",
                    arrayOf(calendarId.toString()), null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { known.add(it) }
                    }
                } ?: throw IllegalStateException("Android-Termine nicht lesbar")
            }
            val newEvents = CalDavReader(server, username, password).fetchNewEvents(known)
            var imported = 0
            var skipped = 0
            for (remote in newEvents) {
                if (Thread.currentThread().isInterrupted) break
                val event = ICalendarEvent.parse(remote.text)
                if (event == null) { skipped++;continue }
                val calendarId = allCalendars[remote.calendarUrl]
                    ?: createCalendar(account, remote.calendarUrl).also {
                        allCalendars[remote.calendarUrl] = it
                    }
                val values = ContentValues().apply {
                    put(events.CALENDAR_ID, calendarId)
                    put(events.TITLE, event.title)
                    put(events.DTSTART, event.startMillis)
                    put(events.DTEND, event.endMillis)
                    put(events.ALL_DAY, if (event.allDay) 1 else 0)
                    put(events.EVENT_TIMEZONE, "UTC")
                    put(events.DESCRIPTION, event.description)
                    put(events.EVENT_LOCATION, event.location)
                    put(events.SYNC_DATA1, remote.source)
                    put(events.STATUS, events.STATUS_CONFIRMED)
                }
                val result = context.contentResolver.insert(syncUri(events.CONTENT_URI, account), values)
                if (result == null) throw IllegalStateException("Termin konnte nicht gespeichert werden")
                imported++
                syncResult.stats.numInserts++
            }
            report("Kalenderimport abgeschlossen: $imported neue Termine, $skipped komplexe Einträge übersprungen.")
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
            .appendQueryParameter(calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(calendars.ACCOUNT_TYPE, account.type)
            .build()

    private fun createCalendar(account: Account, location: String): Long {
        val title = location.trimEnd('/').substringAfterLast('/').take(80).ifEmpty { "Cal45" }
        val values = ContentValues().apply {
            put(calendars.ACCOUNT_NAME, account.name)
            put(calendars.ACCOUNT_TYPE, account.type)
            put(calendars.NAME, "Cal45: $title")
            put(calendars.CALENDAR_DISPLAY_NAME, "Cal45 · $title")
            put(calendars.CALENDAR_LOCATION, location)
            put(calendars.CALENDAR_COLOR, android.graphics.Color.rgb(96, 165, 250))
            put(calendars.CALENDAR_ACCESS_LEVEL, calendars.CAL_ACCESS_READ)
            put(calendars.OWNER_ACCOUNT, account.name)
            put(calendars.VISIBLE, 1)
            put(calendars.SYNC_EVENTS, 1)
            put(calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val inserted = context.contentResolver.insert(syncUri(calendars.CONTENT_URI, account), values)
            ?: throw IllegalStateException("Cal45-Kalender konnte nicht erstellt werden")
        return inserted.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("Neue Kalender-ID fehlt")
    }
}

class CalendarSyncService : android.app.Service() {
    private val adapter by lazy { CalendarSyncAdapter(this) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

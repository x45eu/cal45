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
 * Manual, remote-to-local import of standalone CalDAV CalendarContract.Events.
 * Never uploads, updates or deletes events, even when remote data changes.
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

            // Source IDs are full HTTPS resource URLs; previously imported
            // events stay untouched even if a server-side event changes.
            val known = HashSet<String>()
            for (calendarId in allCalendars.values) {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.SYNC_DATA1),
                    "${CalendarContract.Events.CALENDAR_ID}=?",
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
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DTSTART, event.startMillis)
                    put(CalendarContract.Events.DTEND, event.endMillis)
                    put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DESCRIPTION, event.description)
                    put(CalendarContract.Events.EVENT_LOCATION, event.location)
                    put(CalendarContract.Events.SYNC_DATA1, remote.source)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                }
                val result = context.contentResolver.insert(syncUri(CalendarContract.Events.CONTENT_URI, account), values)
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

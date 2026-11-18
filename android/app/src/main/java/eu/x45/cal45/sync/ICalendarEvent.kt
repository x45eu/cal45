package eu.x45.cal45.sync

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Conservative iCalendar parser. Supports individual VEVENTs and finite,
 * elementary UTC/all-day RRULEs. Complex exceptions fail closed.
 */
internal object ICalendarEvent {
    data class Parsed(
        val uid: String,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val description: String,
        val location: String,
        val rrule: String? = null
    )
    private val date = DateTimeFormatter.BASIC_ISO_DATE
    private val local = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val offsetHHmm = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXX")
    private val offsetHHcolon = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXXX")
    private data class Stamp(val millis: Long, val allDay: Boolean)

    private fun timestamp(property: String, value: String): Stamp? {
        if (value.length > 64 || property.length > 256) return null
        val tokens = property.split(';')
        val params = tokens.drop(1).associate { token ->
            val eq = token.indexOf('=')
            if (eq < 1) return null
            token.substring(0, eq).uppercase() to token.substring(eq + 1).trim('"')
        }
        return try {
            if (params["VALUE"] == "DATE" || (!value.contains('T') && value.length == 8)) {
                if (value.length != 8) return null
                val instant = LocalDate.parse(value, date).atStartOfDay(ZoneOffset.UTC).toInstant()
                Stamp(instant.toEpochMilli(), true)
            } else if (value.endsWith("Z") && value.length == 16) {
                val time = LocalDateTime.parse(value.dropLast(1), local)
                Stamp(time.toInstant(ZoneOffset.UTC).toEpochMilli(), false)
            } else if (value.matches(Regex("[0-9]{8}T[0-9]{6}[+-][0-9]{4}"))) {
                Stamp(OffsetDateTime.parse(value, offsetHHmm).toInstant().toEpochMilli(), false)
            } else if (value.matches(Regex("[0-9]{8}T[0-9]{6}[+-][0-9]{2}:[0-9]{2}"))) {
                Stamp(OffsetDateTime.parse(value, offsetHHcolon).toInstant().toEpochMilli(), false)
            } else if (value.length == 15 && value[8] == 'T') {
                val zone = params["TZID"] ?: return null // floating time is ambiguous
                val zoneId = ZoneId.of(zone)
                val localTime = LocalDateTime.parse(value, local)
                // Reject ambiguous and nonexistent local times: no silent DST shift.
                if (zoneId.rules.getValidOffsets(localTime).size != 1) return null
                Stamp(localTime.atZone(zoneId).toInstant().toEpochMilli(), false)
            } else null
        } catch (_: Exception) { null }
    }
    private fun unescape(s: String): String =
        s.replace("\\n", "\n", ignoreCase = true)
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")

    fun parse(text: String): Parsed? {
        if (text.length > 256 * 1024 || !text.contains("BEGIN:VCALENDAR", true) ||
            !text.contains("END:VCALENDAR", true)) return null
        val unfolded = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] += line.drop(1)
            } else if (line.isNotEmpty()) unfolded.add(line)
            if (unfolded.size > 3000) return null
        }
        var inEvent = false
        var events = 0
        var uid = ""
        var title = ""
        var start: Stamp? = null
        var end: Stamp? = null
        var description = ""
        var location = ""
        var unsupported = false
        var recurrenceRaw: String? = null
        var startSource: String? = null
        var nested = 0
        for (line in unfolded) {
            if (line.equals("BEGIN:VEVENT", true)) {
                events++
                if (events != 1 || inEvent) return null
                inEvent = true
                continue
            }
            if (line.equals("END:VEVENT", true)) {
                if (!inEvent || nested != 0) return null
                inEvent = false
                continue
            }
            if (!inEvent) continue
            if (line.startsWith("BEGIN:", true)) {nested++;continue}
            if (line.startsWith("END:", true)) {
                if (nested == 0) return null
                nested--
                continue
            }
            if (nested != 0) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val rawName = line.substring(0, separator)
            val name = rawName.substringBefore(';').uppercase()
            val value = line.substring(separator + 1)
            when (name) {
                "UID" -> uid = value.trim().take(512)
                "SUMMARY" -> title = unescape(value).trim().take(256)
                "DESCRIPTION" -> description = unescape(value).take(4096)
                "LOCATION" -> location = unescape(value).take(256)
                "DTSTART" -> { start = timestamp(rawName, value); startSource = value }
                "DTEND" -> end = timestamp(rawName, value)
                "STATUS" -> if (value.equals("CANCELLED", true)) unsupported = true
                "RRULE" -> {
                    if (recurrenceRaw != null) unsupported = true
                    recurrenceRaw = value
                }
                "RDATE", "EXDATE", "RECURRENCE-ID", "DURATION" ->
                    unsupported = true
            }
        }
        if (events != 1 || inEvent || unsupported || uid.isBlank() || title.isBlank()) return null
        val from = start ?: return null
        val to = end ?: return null
        if (from.allDay != to.allDay || to.millis <= from.millis) return null
        // Prevent unbounded spans and implausible calendar timestamps.
        if (to.millis - from.millis > 366L * 24 * 60 * 60 * 1000) return null
        val rule = if (recurrenceRaw != null) {
            // Import only UTC start timestamps or all-day dates. Local TZIDs
            // require wall-time handling across DST and are not yet safe.
            val source = startSource ?: return null
            if (!from.allDay && !Regex("[0-9]{8}T[0-9]{6}Z").matches(source))
                return null
            val valid = SafeRecurrence.parse(recurrenceRaw) ?: return null
            val until = valid.split(';').firstOrNull { it.startsWith("UNTIL=") }
                ?.substringAfter('=')
            if (until != null) {
                if (from.allDay && !Regex("[0-9]{8}").matches(until)) return null
                if (!from.allDay && !Regex("[0-9]{8}T[0-9]{6}Z").matches(until)) return null
                val untilStamp = timestamp(
                    if (from.allDay) "DTSTART;VALUE=DATE" else "DTSTART", until
                ) ?: return null
                if (untilStamp.millis < from.millis ||
                    untilStamp.millis - from.millis > 732L * 86400000L) return null
            }
            if (SafeRecurrence.androidDuration(to.millis - from.millis, from.allDay) == null)
                return null
            valid
        } else null
        return Parsed(uid, title, from.millis, to.millis, from.allDay, description, location, rule)
    }
}

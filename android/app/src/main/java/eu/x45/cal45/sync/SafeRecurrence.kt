package eu.x45.cal45.sync

/**
 * Safe RFC5545 subset for Android recurring-event creation.
 * Import only finite, elementary daily/weekly/monthly series. Unknown rule
 * parameters, complex exceptions and unbounded recurrence fail closed.
 */
internal object SafeRecurrence {
    private val dayOrder = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    fun parse(value: String): String? {
        if (value.isBlank() || value.length > 256 ||
            value.any { !it.isLetterOrDigit() && it !in ";=,-" }) return null
        val parts = LinkedHashMap<String, String>()
        for (item in value.split(';')) {
            val split = item.split('=', limit = 2)
            if (split.size != 2 || split[0].isEmpty() || split[1].isEmpty() ||
                parts.put(split[0], split[1]) != null) return null
        }
        val freq = parts["FREQ"] ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY")) return null
        if (parts.keys.any {
                it !in setOf("FREQ", "COUNT", "UNTIL", "INTERVAL", "BYDAY", "BYMONTHDAY")
            }) return null
        val countRaw = parts["COUNT"]
        val untilRaw = parts["UNTIL"]
        if ((countRaw == null) == (untilRaw == null)) return null
        val count = countRaw?.toIntOrNull()
        if (countRaw != null && (count == null || count !in 1..366)) return null
        if (untilRaw != null && !Regex("(?:[0-9]{8}|[0-9]{8}T[0-9]{6}Z)")
                .matches(untilRaw)) return null
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        if (interval !in 1..12) return null
        val byDays = parts["BYDAY"]?.split(',')
        if (byDays != null && (freq != "WEEKLY" || byDays.isEmpty() ||
            byDays.size > 7 || byDays.toSet().size != byDays.size ||
            byDays.any { it !in dayOrder })) return null
        val byMonthDay = parts["BYMONTHDAY"]?.toIntOrNull()
        if ("BYMONTHDAY" in parts && (freq != "MONTHLY" ||
            byMonthDay == null || byMonthDay !in 1..28)) return null
        // Prevent mixed or advanced BYxxx rules that would change occurrence math.
        if (byMonthDay != null && byDays != null) return null
        return buildString {
            append("FREQ=").append(freq)
            append(";INTERVAL=").append(interval)
            if (count != null) append(";COUNT=").append(count)
            if (untilRaw != null) append(";UNTIL=").append(untilRaw)
            if (byDays != null) append(";BYDAY=").append(
                dayOrder.filter { it in byDays }.joinToString(",")
            )
            if (byMonthDay != null) append(";BYMONTHDAY=").append(byMonthDay)
        }
    }

    fun androidDuration(milliseconds: Long, allDay: Boolean): String? {
        if (milliseconds <= 0 || milliseconds > 366L * 86400000L) return null
        if (allDay) {
            if (milliseconds % 86400000L != 0L) return null
            return "P${milliseconds / 86400000L}D"
        }
        if (milliseconds % 1000L != 0L) return null
        return "PT${milliseconds / 1000L}S"
    }
}

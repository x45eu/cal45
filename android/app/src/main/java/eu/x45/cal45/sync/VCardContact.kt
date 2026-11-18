package eu.x45.cal45.sync

/**
 * Conservative vCard 3/4 reader for initial contact import only.
 * Unsupported fields are ignored; remote cards are never modified.
 */
internal object VCardContact {
    data class Parsed(val name: String, val phones: List<String>, val emails: List<String>)

    fun parse(text: String): Parsed? {
        if (text.length > 256 * 1024 || !text.contains("BEGIN:VCARD", true) ||
            !text.contains("END:VCARD", true)) return null
        val unfolded = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] += line.drop(1)
            } else if (line.isNotEmpty()) {
                unfolded.add(line)
            }
            if (unfolded.size > 3000) return null
        }
        var fullName = ""
        var structured = ""
        val phones = LinkedHashSet<String>()
        val emails = LinkedHashSet<String>()
        for (line in unfolded) {
            val pos = line.indexOf(':')
            if (pos <= 0) continue
            val field = line.substring(0, pos)
            if (field.contains("ENCODING=", ignoreCase = true)) continue
            val key = field.substringBefore(';').substringAfterLast('.').uppercase()
            val value = line.substring(pos + 1)
                .replace("\\n", "\n", ignoreCase = true)
                .replace("\\,", ",")
                .replace("\\;", ";")
                .replace("\\\\", "\\")
                .trim()
                .take(512)
            when (key) {
                "FN" -> if (fullName.isEmpty()) fullName = value
                "N" -> if (structured.isEmpty()) structured = value.split(';').take(2)
                    .reversed().joinToString(" ").trim()
                "TEL" -> if (value.isNotEmpty() && phones.size < 10)
                    phones.add(value.removePrefix("tel:").removePrefix("TEL:"))
                "EMAIL" -> if (value.isNotEmpty() && emails.size < 10) emails.add(value)
            }
        }
        val label = (fullName.ifEmpty { structured }).trim()
        if (label.isBlank()) return null
        return Parsed(label.take(256), phones.toList(), emails.toList())
    }
}

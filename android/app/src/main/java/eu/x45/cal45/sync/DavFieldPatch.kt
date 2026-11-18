package eu.x45.cal45.sync

/**
 * Lossless *single-property* patch preparation for previously verified
 * DAV documents. Not a general vCard/iCalendar serializer.
 *
 * The only editable properties are FN in a single VCARD and SUMMARY in
 * a single standalone VEVENT. Everything else is retained byte-for-byte:
 * original line endings, unknown fields, photos, attendees, extensions,
 * timezone blocks, and any other content.
 *
 * Unsupported structures fail closed. This code has no networking and
 * is not wired to any DAV PUT or Android SyncAdapter.
 */
internal object DavFieldPatch {
    enum class Target(val outer: String, val inner: String?, val property: String) {
        CONTACT_NAME("VCARD", null, "FN"),
        EVENT_TITLE("VCALENDAR", "VEVENT", "SUMMARY")
    }

    sealed class Result {
        data class Prepared(
            val originalText: String,
            val patchedText: String,
            val oldValue: String,
            val newValue: String,
            val changedLines: Int
        ) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private data class Line(val content: String, val separator: String)

    private fun splitLines(input: String): List<Line>? {
        val lines = ArrayList<Line>()
        var start = 0
        var index = 0
        while (index < input.length) {
            when (input[index]) {
                '\r' -> {
                    val separator = if (index + 1 < input.length &&
                        input[index + 1] == '\n') "\r\n" else "\r"
                    lines.add(Line(input.substring(start, index), separator))
                    index += separator.length
                    start = index
                }
                '\n' -> {
                    lines.add(Line(input.substring(start, index), "\n"))
                    index++
                    start = index
                }
                else -> index++
            }
            if (lines.size > 3000) return null
        }
        if (start < input.length) lines.add(Line(input.substring(start), ""))
        return lines
    }

    private fun plainText(value: String): Boolean =
        value.length in 1..256 &&
            value.none { it == '\r' || it == '\n' || it == '\u0000' ||
                it.code < 32 || it.code == 127 } &&
            value.isNotBlank()

    private fun escapeText(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")

    private fun unescapeText(value: String): String? {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\') {
                if (index + 1 == value.length) return null
                val c = value[index + 1]
                when (c) {
                    '\\', ',', ';' -> out.append(c)
                    'n', 'N' -> out.append('\n')
                    else -> return null
                }
                index += 2
            } else {
                out.append(value[index])
                index++
            }
        }
        return out.toString()
    }

    fun prepare(
        original: String, target: Target,
        expectedOldValue: String, newValue: String
    ): Result {
        if (original.length > 256 * 1024 || original.isEmpty() ||
            original.contains('\u0000') ||
            !original.contains("BEGIN:", ignoreCase = true))
            return Result.Rejected("Invalid document")
        if (!plainText(expectedOldValue) || !plainText(newValue))
            return Result.Rejected("Unsupported text")
        val lines = splitLines(original) ?: return Result.Rejected("Too many lines")
        if (lines.isEmpty()) return Result.Rejected("Empty document")
        if (lines.any { it.separator == "\r" })
            return Result.Rejected("Unsupported line ending")
        val newline = lines.firstOrNull { it.separator.isNotEmpty() }?.separator
            ?: return Result.Rejected("Missing line ending")
        if (lines.any { it.separator.isNotEmpty() && it.separator != newline })
            return Result.Rejected("Mixed line endings")

        // Only one top-level object, one target component, no nested events,
        // no folded target property (continuations would otherwise be lost).
        val stack = ArrayList<String>()
        var outerCount = 0
        var innerCount = 0
        var insideTarget = false
        var targetIndex = -1
        for ((index, line) in lines.withIndex()) {
            val value = line.content
            if (value.startsWith("BEGIN:", ignoreCase = true)) {
                val component = value.substringAfter(':')
                if (stack.isEmpty()) {
                    if (!component.equals(target.outer, true) || outerCount++ != 0)
                        return Result.Rejected("Multiple document roots")
                } else if (target.inner != null && stack.size == 1 &&
                    component.equals(target.inner, true)) {
                    innerCount++
                    if (innerCount > 1) return Result.Rejected("Multiple events")
                    insideTarget = true
                } else if (target.inner == null && stack.size == 1) {
                    return Result.Rejected("Nested vCard content")
                }
                if (stack.size >= 8) return Result.Rejected("Component nesting too deep")
                stack.add(component.uppercase())
                continue
            }
            if (value.startsWith("END:", ignoreCase = true)) {
                val component = value.substringAfter(':').uppercase()
                if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != component)
                    return Result.Rejected("Unbalanced component names")
                if (target.inner != null && stack.size == 1 && insideTarget &&
                    component.equals(target.inner, true)) insideTarget = false
                continue
            }
            val atTarget = if (target.inner == null) stack.size == 1 else
                stack.size == 2 && insideTarget
            if (!atTarget) continue
            val colon = value.indexOf(':')
            if (colon < 0) continue
            val field = value.substring(0, colon)
            // Parameterized properties cannot safely be rewritten because
            // language/encoding/alternate representation semantics may differ.
            if (field.equals(target.property, true)) {
                if (targetIndex >= 0) return Result.Rejected("Duplicate property")
                if (index + 1 < lines.size && lines[index + 1].content
                        .startsWith(" ") ||
                    index + 1 < lines.size && lines[index + 1].content
                        .startsWith("\t")) {
                    return Result.Rejected("Folded target property")
                }
                targetIndex = index
            } else if (field.substringBefore(';').equals(target.property, true)) {
                return Result.Rejected("Parameterized target property")
            }
            if (target.inner != null && field.equals("RRULE", true))
                return Result.Rejected("Recurring event")
        }
        if (stack.isNotEmpty() || outerCount != 1 ||
            (target.inner != null && innerCount != 1))
            return Result.Rejected("Unbalanced components")
        if (targetIndex < 0) return Result.Rejected("Required property missing")
        val current = lines[targetIndex].content.substringAfter(':')
        val decoded = unescapeText(current) ?: return Result.Rejected("Invalid escaping")
        if (decoded != expectedOldValue) return Result.Rejected("Old value changed")
        if (newValue == expectedOldValue) return Result.Rejected("No change")
        val replacement = target.property + ":" + escapeText(newValue)
        val output = buildString(original.length + 300) {
            for ((index, line) in lines.withIndex()) {
                append(if (index == targetIndex) replacement else line.content)
                append(line.separator)
            }
        }
        // Defensive invariant: patching must only replace the intended
        // property and must never silently introduce a new section.
        if (output == original || !output.contains("END:" + target.outer))
            return Result.Rejected("Invalid patch")
        return Result.Prepared(original, output, expectedOldValue, newValue, 1)
    }
}

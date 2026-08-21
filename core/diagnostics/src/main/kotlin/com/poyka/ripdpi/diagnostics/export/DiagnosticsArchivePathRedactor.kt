package com.poyka.ripdpi.diagnostics.export

internal object DiagnosticsArchivePathRedactor {
    private val logicalLineContentRegex = Regex("[^\\r\\n]+")
    private val knownAppLogcatPrefixRegex =
        Regex(
            "^(?:\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+" +
                "(?:(?:\\d+\\s+){2}[VDIWEF]\\s+(?:ripdpi|ripdpi-native)|" +
                "[VDIWEF]/(?:ripdpi|ripdpi-native)):\\s*|" +
                "[VDIWEF]/(?:ripdpi|ripdpi-native)\\(\\s*\\d+\\):\\s*)",
            RegexOption.IGNORE_CASE,
        )
    private val semanticPathKeys = setOf("file", "path", "filepath")

    fun redact(value: String): String = logicalLineContentRegex.replace(value) { match -> redactLine(match.value) }

    // Ambiguous absolute paths have no reliable boundary once spaces, punctuation, or escape layers are allowed.
    // Fail closed per logical line, retaining only a strict known-app Android logcat prefix when present.
    private fun redactLine(line: String): String {
        val semanticRedaction = redactSemanticQuotedPathValues(line)
        if (!semanticRedaction.malformed && !containsResidualAbsolutePath(semanticRedaction.value)) {
            return semanticRedaction.value
        }
        val logcatPrefix = knownAppLogcatPrefixRegex.find(line)?.value.orEmpty()
        return logcatPrefix + "<path-redacted>"
    }

    private data class SemanticPathRedaction(
        val value: String,
        val malformed: Boolean,
    )

    private fun redactSemanticQuotedPathValues(line: String): SemanticPathRedaction {
        val redacted = StringBuilder(line.length)
        var copiedUntil = 0
        var searchFrom = 0
        while (searchFrom < line.length) {
            val keyStart = line.indexOf('"', searchFrom)
            val keyEnd = if (keyStart >= 0) findQuotedStringEnd(line, keyStart + 1) else null
            if (keyStart < 0 || keyEnd == null) {
                searchFrom = line.length
            } else {
                val key = line.substring(keyStart + 1, keyEnd).lowercase()
                val colon = line.skipWhitespaceFrom(keyEnd + 1)
                val valueQuote = line.skipWhitespaceFrom(colon + 1)
                val isSemanticPathValue =
                    key in semanticPathKeys && line.getOrNull(colon) == ':' && line.getOrNull(valueQuote) == '"'
                if (isSemanticPathValue) {
                    val valueEnd =
                        findQuotedStringEnd(line, valueQuote + 1)
                            ?: return SemanticPathRedaction(line, malformed = true)
                    redacted.append(line, copiedUntil, valueQuote + 1)
                    redacted.append("<path-redacted>")
                    copiedUntil = valueEnd
                    searchFrom = valueEnd + 1
                } else {
                    searchFrom = keyEnd + 1
                }
            }
        }
        redacted.append(line, copiedUntil, line.length)
        return SemanticPathRedaction(redacted.toString(), malformed = false)
    }

    private fun findQuotedStringEnd(
        value: String,
        start: Int,
    ): Int? {
        var escaped = false
        for (index in start until value.length) {
            when {
                escaped -> escaped = false
                value[index] == '\\' -> escaped = true
                value[index] == '"' -> return index
            }
        }
        return null
    }

    private fun String.skipWhitespaceFrom(start: Int): Int {
        var index = start
        while (index < length && this[index].isWhitespace()) index += 1
        return index
    }

    private fun containsResidualAbsolutePath(line: String): Boolean =
        line.containsPercentEncodedPathSeparator() ||
            line.indices.any { index ->
                val character = line[index]
                // A backslash may be a Windows separator, a JSON escape layer, or the introducer for
                // a case-insensitive Unicode slash/backslash escape. None is safe to preserve verbatim.
                (character == '/' && line.isPathBoundaryBefore(index)) ||
                    line.hasDrivePathStartAt(index) ||
                    character == '\\'
            }

    private fun String.containsPercentEncodedPathSeparator(): Boolean =
        indices.any { index -> this[index] == '%' && hasPercentEncodedPathSeparatorAt(index) }

    private fun String.hasPercentEncodedPathSeparatorAt(percentIndex: Int): Boolean {
        var encodedByteStart = percentIndex + 1
        while (regionMatches(encodedByteStart, "25", 0, 2, ignoreCase = true)) encodedByteStart += 2
        return regionMatches(encodedByteStart, "2f", 0, 2, ignoreCase = true) ||
            regionMatches(encodedByteStart, "5c", 0, 2, ignoreCase = true)
    }

    private fun String.isPathBoundaryBefore(index: Int): Boolean =
        index == 0 || (!this[index - 1].isLetterOrDigit() && this[index - 1] != '_')

    private fun String.hasDrivePathStartAt(index: Int): Boolean =
        isPathBoundaryBefore(index) &&
            getOrNull(index)?.isLetter() == true &&
            getOrNull(index + 1) == ':' &&
            (getOrNull(index + 2) == '/' || getOrNull(index + 2) == '\\')
}

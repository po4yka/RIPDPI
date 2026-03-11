package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultSplitMarker = "1"
const val DefaultFakeOffsetMarker = "0"
const val DefaultTlsRecordMarker = "0"

private val NumericOffsetPattern = Regex("^[+-]?\\d+$")
private val NamedOffsetPattern = Regex("^(abs|host|endhost|sld|midsld|endsld|method|extlen|sniext)([+-]\\d+)?$")
private val LegacyOffsetPattern = Regex("^[+-]?\\d+\\+(?:s(?:s|e|m|r)?|h(?:s|e|m|r)?|n(?:s|e|m|r)?)$")

fun AppSettings.effectiveSplitMarker(): String = splitMarker.normalizedOrElse { legacyMarkerExpression(splitPosition, splitAtHost) }

fun AppSettings.effectiveFakeOffsetMarker(): String = fakeOffsetMarker.normalizedOrElse { fakeOffset.toString() }

fun AppSettings.effectiveTlsRecordMarker(): String =
    tlsrecMarker.normalizedOrElse {
        // Preserve the old TLS-record toggle semantics by migrating it to a host-relative marker.
        legacyMarkerExpression(tlsrecPosition, tlsrecAtSni)
    }

fun normalizeOffsetExpression(value: String, defaultValue: String): String = value.trim().ifEmpty { defaultValue }

fun isValidOffsetExpression(value: String): Boolean {
    if (value.isBlank()) {
        return false
    }

    val parts = value.trim().split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() }) {
        return false
    }

    if (!isValidOffsetBase(parts[0])) {
        return false
    }

    if (parts.size >= 2) {
        val repeats = parts[1].toIntOrNull() ?: return false
        if (repeats <= 0) {
            return false
        }
    }

    if (parts.size == 3 && parts[2].toIntOrNull() == null) {
        return false
    }

    return true
}

private fun String.normalizedOrElse(fallback: () -> String): String = trim().ifEmpty(fallback)

private fun legacyMarkerExpression(position: Int, useHostMarker: Boolean): String =
    if (useHostMarker) {
        markerExpression("host", position)
    } else {
        position.toString()
    }

private fun markerExpression(base: String, delta: Int): String =
    when {
        delta == 0 -> base
        delta > 0 -> "$base+$delta"
        else -> "$base$delta"
    }

private fun isValidOffsetBase(value: String): Boolean =
    NumericOffsetPattern.matches(value) || NamedOffsetPattern.matches(value) || LegacyOffsetPattern.matches(value)

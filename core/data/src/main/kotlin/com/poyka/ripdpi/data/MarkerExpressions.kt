package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultSplitMarker = "1"
const val DefaultDisoobSplitMarker = "3+s"
const val DefaultFakeOffsetMarker = "0"
const val DefaultTlsRecordMarker = "0"
const val DefaultTlsRandRecFragmentCount = 4
const val DefaultTlsRandRecMinFragmentSize = 16
const val DefaultTlsRandRecMaxFragmentSize = 96
const val AdaptiveMarkerBalanced = "auto(balanced)"
const val AdaptiveMarkerHost = "auto(host)"
const val AdaptiveMarkerMidSld = "auto(midsld)"
const val AdaptiveMarkerEndHost = "auto(endhost)"
const val AdaptiveMarkerMethod = "auto(method)"
const val AdaptiveMarkerSniExt = "auto(sniext)"
const val AdaptiveMarkerExtLen = "auto(extlen)"

private val NumericOffsetPattern = Regex("^[+-]?\\d+$")
private val NamedOffsetPattern = Regex("^(abs|host|endhost|sld|midsld|endsld|method|extlen|sniext)([+-]\\d+)?$")
private val LegacyOffsetPattern = Regex("^[+-]?\\d+\\+(?:s(?:s|e|m|r)?|h(?:s|e|m|r)?|n(?:s|e|m|r)?)$")
private val AdaptiveOffsetPattern =
    Regex("^auto\\((balanced|host|midsld|endhost|method|sniext|extlen)\\)$", RegexOption.IGNORE_CASE)
private val AdaptiveOffsetPresetPattern =
    Regex("^auto\\((balanced|host|midsld|endhost|method|sniext|extlen)\\)$", RegexOption.IGNORE_CASE)

fun AppSettings.effectiveSplitMarker(): String =
    splitMarker.normalizedOrElse {
        legacyMarkerExpression(splitPosition, splitAtHost)
    }.let { marker ->
        if (shouldUseLegacyDisoobDefaultMarker(marker)) {
            DefaultDisoobSplitMarker
        } else {
            marker
        }
    }

fun AppSettings.effectiveFakeOffsetMarker(): String = fakeOffsetMarker.normalizedOrElse { fakeOffset.toString() }

fun AppSettings.effectiveTlsRecordMarker(): String =
    tlsrecMarker.normalizedOrElse {
        // Preserve the old TLS-record toggle semantics by migrating it to a host-relative marker.
        legacyMarkerExpression(tlsrecPosition, tlsrecAtSni)
    }

fun normalizeOffsetExpression(
    value: String,
    defaultValue: String,
): String = value.trim().ifEmpty { defaultValue }

fun isValidOffsetExpression(value: String): Boolean {
    if (value.isBlank()) {
        return false
    }
    if (isAdaptiveOffsetExpression(value)) {
        return true
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

fun isAdaptiveOffsetExpression(value: String): Boolean = AdaptiveOffsetPattern.matches(value.trim())

fun adaptiveOffsetPreset(value: String): String? =
    AdaptiveOffsetPresetPattern
        .matchEntire(value.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()

fun formatOffsetExpressionLabel(value: String): String =
    when (adaptiveOffsetPreset(value)) {
        "balanced" -> "adaptive balanced"
        "host" -> "adaptive host/SNI start"
        "midsld" -> "adaptive host/SNI middle"
        "endhost" -> "adaptive host/SNI end"
        "method" -> "adaptive HTTP method"
        "sniext" -> "adaptive TLS SNI extension"
        "extlen" -> "adaptive TLS extensions length"
        else -> value
    }

private fun String.normalizedOrElse(fallback: () -> String): String = trim().ifEmpty(fallback)

private fun AppSettings.shouldUseLegacyDisoobDefaultMarker(marker: String): Boolean =
    tcpChainStepsCount == 0 &&
        desyncMethod.equals("disoob", ignoreCase = true) &&
        marker == DefaultSplitMarker &&
        splitPosition == 1 &&
        !splitAtHost

private fun legacyMarkerExpression(
    position: Int,
    useHostMarker: Boolean,
): String =
    if (useHostMarker) {
        markerExpression("host", position)
    } else {
        position.toString()
    }

private fun markerExpression(
    base: String,
    delta: Int,
): String =
    when {
        delta == 0 -> base
        delta > 0 -> "$base+$delta"
        else -> "$base$delta"
    }

private fun isValidOffsetBase(value: String): Boolean =
    NumericOffsetPattern.matches(value) || NamedOffsetPattern.matches(value) || LegacyOffsetPattern.matches(value)

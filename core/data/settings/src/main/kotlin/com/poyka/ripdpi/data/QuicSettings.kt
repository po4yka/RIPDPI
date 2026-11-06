package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val QuicInitialModeDisabled = "disabled"
const val QuicInitialModeRoute = "route"
const val QuicInitialModeRouteAndCache = "route_and_cache"
const val QuicFakeProfileDisabled = "disabled"
const val QuicFakeProfileCompatDefault = "compat_default"
const val QuicFakeProfileRealisticInitial = "realistic_initial"
const val DefaultQuicFakeHost = "www.wikipedia.org"

private fun hasInvalidHostnameStructure(trimmed: String): Boolean =
    trimmed.isEmpty() || trimmed.contains(':') || trimmed.startsWith('.') ||
        trimmed.endsWith('.') || trimmed.contains("..")

private fun containsInvalidHostnameChar(trimmed: String): Boolean =
    !trimmed.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '.' }

private val KnownQuicInitialModes =
    setOf(
        QuicInitialModeDisabled,
        QuicInitialModeRoute,
        QuicInitialModeRouteAndCache,
    )

private val KnownQuicFakeProfiles =
    setOf(
        QuicFakeProfileDisabled,
        QuicFakeProfileCompatDefault,
        QuicFakeProfileRealisticInitial,
    )

fun normalizeQuicInitialMode(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownQuicInitialModes } ?: QuicInitialModeRouteAndCache
}

fun AppSettings.effectiveQuicInitialMode(): String = normalizeQuicInitialMode(quicInitialMode)

fun AppSettings.effectiveQuicSupportV1(): Boolean =
    if (quicInitialMode.isBlank() && !quicSupportV1 && !quicSupportV2) {
        true
    } else {
        quicSupportV1
    }

fun AppSettings.effectiveQuicSupportV2(): Boolean =
    if (quicInitialMode.isBlank() && !quicSupportV1 && !quicSupportV2) {
        true
    } else {
        quicSupportV2
    }

fun quicInitialModeUsesRouting(mode: String): Boolean = normalizeQuicInitialMode(mode) != QuicInitialModeDisabled

fun quicInitialModeCachesHosts(mode: String): Boolean = normalizeQuicInitialMode(mode) == QuicInitialModeRouteAndCache

fun normalizeQuicFakeProfile(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownQuicFakeProfiles } ?: QuicFakeProfileDisabled
}

@Suppress("ReturnCount")
fun normalizeQuicFakeHost(value: String): String {
    val trimmed = value.trim().trimEnd('.').lowercase()
    if (hasInvalidHostnameStructure(trimmed)) {
        return ""
    }
    if (containsInvalidHostnameChar(trimmed)) {
        return ""
    }
    if (trimmed.split('.').any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }) {
        return ""
    }
    val isIpv4Literal = NumericIp.parseAddress(trimmed)?.isIPv4 == true
    return if (isIpv4Literal) "" else trimmed
}

fun AppSettings.effectiveQuicFakeProfile(): String = normalizeQuicFakeProfile(quicFakeProfile)

fun AppSettings.effectiveQuicFakeHost(): String = normalizeQuicFakeHost(quicFakeHost)

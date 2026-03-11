package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val QuicInitialModeDisabled = "disabled"
const val QuicInitialModeRoute = "route"
const val QuicInitialModeRouteAndCache = "route_and_cache"
const val QuicFakeProfileDisabled = "disabled"
const val QuicFakeProfileCompatDefault = "compat_default"
const val QuicFakeProfileRealisticInitial = "realistic_initial"
const val DefaultQuicFakeHost = "www.wikipedia.org"

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

fun quicInitialModeCachesHosts(mode: String): Boolean =
    normalizeQuicInitialMode(mode) == QuicInitialModeRouteAndCache

fun normalizeQuicFakeProfile(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownQuicFakeProfiles } ?: QuicFakeProfileDisabled
}

fun normalizeQuicFakeHost(value: String): String {
    val trimmed = value.trim().trimEnd('.').lowercase()
    if (trimmed.isEmpty() || trimmed.contains(':') || trimmed.startsWith('.') || trimmed.endsWith('.') || trimmed.contains("..")) {
        return ""
    }
    if (!trimmed.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '.' }) {
        return ""
    }
    if (trimmed.split('.').any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }) {
        return ""
    }
    val ipv4Parts = trimmed.split('.')
    val isIpv4Literal =
        ipv4Parts.size == 4 &&
            ipv4Parts.all { part ->
                part.toIntOrNull()?.let { octet -> octet in 0..255 && octet.toString() == part } == true
            }
    return if (isIpv4Literal) "" else trimmed
}

fun AppSettings.effectiveQuicFakeProfile(): String = normalizeQuicFakeProfile(quicFakeProfile)

fun AppSettings.effectiveQuicFakeHost(): String = normalizeQuicFakeHost(quicFakeHost)

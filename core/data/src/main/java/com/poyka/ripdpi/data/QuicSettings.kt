package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val QuicInitialModeDisabled = "disabled"
const val QuicInitialModeRoute = "route"
const val QuicInitialModeRouteAndCache = "route_and_cache"

private val KnownQuicInitialModes =
    setOf(
        QuicInitialModeDisabled,
        QuicInitialModeRoute,
        QuicInitialModeRouteAndCache,
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

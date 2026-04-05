package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultAdaptiveFallbackCacheTtlSeconds = 90
const val DefaultAdaptiveFallbackCachePrefixV4 = 24

data class AdaptiveFallbackSettingsModel(
    val enabled: Boolean = true,
    val torst: Boolean = true,
    val tlsErr: Boolean = true,
    val httpRedirect: Boolean = true,
    val connectFailure: Boolean = true,
    val autoSort: Boolean = true,
    val cacheTtlSeconds: Int = DefaultAdaptiveFallbackCacheTtlSeconds,
    val cachePrefixV4: Int = DefaultAdaptiveFallbackCachePrefixV4,
)

fun normalizeAdaptiveFallbackCacheTtlSeconds(value: Int): Int =
    value.takeIf { it > 0 } ?: DefaultAdaptiveFallbackCacheTtlSeconds

fun normalizeAdaptiveFallbackCachePrefixV4(value: Int): Int =
    value.coerceIn(1, 32).takeIf { value > 0 } ?: DefaultAdaptiveFallbackCachePrefixV4

fun AppSettings.toAdaptiveFallbackSettingsModel(): AdaptiveFallbackSettingsModel =
    AdaptiveFallbackSettingsModel(
        enabled = adaptiveFallbackEnabled,
        torst = adaptiveFallbackTorst,
        tlsErr = adaptiveFallbackTlsErr,
        httpRedirect = adaptiveFallbackHttpRedirect,
        connectFailure = adaptiveFallbackConnectFailure,
        autoSort = adaptiveFallbackAutoSort,
        cacheTtlSeconds = normalizeAdaptiveFallbackCacheTtlSeconds(adaptiveFallbackCacheTtlSeconds),
        cachePrefixV4 = normalizeAdaptiveFallbackCachePrefixV4(adaptiveFallbackCachePrefixV4),
    )

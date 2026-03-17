package com.poyka.ripdpi.data.diagnostics

private val rttBandPriority =
    mapOf(
        "lt50" to 0,
        "50_99" to 1,
        "100_249" to 2,
        "250_499" to 3,
        "500_plus" to 4,
        "unknown" to -1,
    )

fun aggregateWinningStrategyFamily(
    winningTcpStrategyFamily: String?,
    winningQuicStrategyFamily: String?,
): String? =
    listOfNotNull(
        winningTcpStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningQuicStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().takeIf { it.isNotEmpty() }?.joinToString(" + ")

fun aggregateRttBand(
    proxyRttBand: String?,
    resolverRttBand: String?,
): String =
    listOfNotNull(proxyRttBand, resolverRttBand)
        .map { it.trim().ifEmpty { "unknown" } }
        .filterNot { it == "unknown" }
        .maxByOrNull { rttBandPriority[it] ?: -1 }
        ?: "unknown"

fun aggregateRetryCount(
    proxyRouteRetryCount: Long,
    tunnelRecoveryRetryCount: Long,
): Long = proxyRouteRetryCount + tunnelRecoveryRetryCount

fun TelemetrySampleEntity.winningStrategyFamily(): String? =
    aggregateWinningStrategyFamily(winningTcpStrategyFamily, winningQuicStrategyFamily)

fun TelemetrySampleEntity.rttBand(): String = aggregateRttBand(proxyRttBand, resolverRttBand)

fun TelemetrySampleEntity.retryCount(): Long =
    aggregateRetryCount(proxyRouteRetryCount, tunnelRecoveryRetryCount)

fun BypassUsageSessionEntity.winningStrategyFamily(): String? =
    aggregateWinningStrategyFamily(winningTcpStrategyFamily, winningQuicStrategyFamily)

fun BypassUsageSessionEntity.rttBand(): String = aggregateRttBand(proxyRttBand, resolverRttBand)

fun BypassUsageSessionEntity.retryCount(): Long =
    aggregateRetryCount(proxyRouteRetryCount, tunnelRecoveryRetryCount)

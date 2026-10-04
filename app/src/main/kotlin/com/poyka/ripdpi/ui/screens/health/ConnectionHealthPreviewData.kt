package com.poyka.ripdpi.ui.screens.health

import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import kotlinx.collections.immutable.toImmutableList

fun previewConnectionHealthUiState(): ConnectionHealthUiState =
    ConnectionHealthUiState(
        rows = previewHealthRows(),
        qualityLossPercent = 8f,
        qualityRttP50Ms = 61,
        qualityRttP95Ms = 140,
        qualitySampleCount = 188,
        qualityJitterMs = 12,
        qualityWindowStartAtMs = 1_700_000_000_000L,
        qualitySource = previewMeasurementSource("proxy"),
        latencySource = previewMeasurementSource("proxy"),
        dnsSource = previewMeasurementSource("tunnel"),
        latencyDistributions = previewLatencyDistributions(),
        dnsCounters =
            DnsCountersUiState(
                queriesTotal = 1_204,
                cacheHits = 938,
                cacheMisses = 266,
                failuresTotal = 12,
            ),
        observedAt = 1_700_000_300_000L,
    )

private fun previewMeasurementSource(source: String) =
    com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource(
        source = source,
        capturedAt = 1_700_000_300_000L,
        serviceStatus = com.poyka.ripdpi.data.AppStatus.Halted,
        telemetryStatus =
            com.poyka.ripdpi.data.RuntimeTelemetryStatus(
                com.poyka.ripdpi.data.RuntimeTelemetryState.Snapshot,
            ),
    )

private fun previewHealthRows() =
    listOf(
        ConnectionHealthRowUiState(
            destinationClass = ConnectionHealthDestinationClass.VK,
            activeStrategy = "tcp:split2+hostfake",
            successCount = 18,
            failureCount = 2,
            attributedCount = 12,
        ),
        ConnectionHealthRowUiState(
            destinationClass = ConnectionHealthDestinationClass.YOUTUBE,
            activeStrategy = "quic:sni_split",
            successCount = 9,
            failureCount = 6,
            attributedCount = 7,
        ),
        ConnectionHealthRowUiState(
            destinationClass = ConnectionHealthDestinationClass.TELEGRAM,
            activeStrategy = "telegram_ws_cover",
            successCount = 21,
            failureCount = 0,
            attributedCount = 13,
        ),
        ConnectionHealthRowUiState(
            destinationClass = ConnectionHealthDestinationClass.GENERIC_TLS,
            activeStrategy = "tcp:record_split",
            successCount = 11,
            failureCount = 4,
            attributedCount = 5,
        ),
    ).toImmutableList()

private fun previewLatencyDistributions() =
    listOf(
        LatencyDistributionUiState(
            kind = LatencyDistributionKind.DnsResolution,
            p50Ms = 18,
            p95Ms = 44,
            p99Ms = 91,
            minMs = 6,
            maxMs = 102,
            count = 312,
        ),
        LatencyDistributionUiState(
            kind = LatencyDistributionKind.TcpConnect,
            p50Ms = 52,
            p95Ms = 140,
            p99Ms = 220,
            minMs = 31,
            maxMs = 240,
            count = 188,
        ),
        LatencyDistributionUiState(
            kind = LatencyDistributionKind.TlsHandshake,
            p50Ms = 96,
            p95Ms = 260,
            p99Ms = 410,
            minMs = 60,
            maxMs = 430,
            count = 174,
        ),
    ).toImmutableList()

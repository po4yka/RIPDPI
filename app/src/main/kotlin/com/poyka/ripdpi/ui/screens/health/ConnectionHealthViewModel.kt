package com.poyka.ripdpi.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.LatencyDistributions
import com.poyka.ripdpi.data.LatencyPercentiles
import com.poyka.ripdpi.service.telemetry.DnsCounterSnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryInsights
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryInsightsRepository
import com.poyka.ripdpi.services.ConnectionHealthBucket
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.services.ConnectionHealthRepository
import com.poyka.ripdpi.services.ConnectionHealthSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ConnectionHealthUiState(
    val rows: List<ConnectionHealthRowUiState> =
        ConnectionHealthDestinationClass.entries.map { destinationClass ->
            ConnectionHealthRowUiState(destinationClass = destinationClass)
        },
    val qualityLossPercent: Int? = null,
    val qualityRttP50Ms: Long? = null,
    val latencyDistributions: List<LatencyDistributionUiState> = emptyList(),
    val dnsCounters: DnsCountersUiState? = null,
    val observedAt: Long = 0L,
) {
    val hasData: Boolean
        get() = rows.any { it.totalCount > 0L }

    val hasRuntimeTelemetry: Boolean
        get() = latencyDistributions.isNotEmpty() || dnsCounters != null
}

enum class LatencyDistributionKind { DnsResolution, TcpConnect, TlsHandshake }

/**
 * One latency-distribution histogram (DNS / TCP / TLS) projected from
 * [com.poyka.ripdpi.data.LatencyPercentiles]. All values are milliseconds; percentiles are
 * HdrHistogram bucket-quantised, so they are an approximate distribution rather than raw samples.
 */
data class LatencyDistributionUiState(
    val kind: LatencyDistributionKind,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val minMs: Long,
    val maxMs: Long,
    val count: Long,
) {
    /** Upper bound used to normalise the percentile bars; never zero so the bar math is safe. */
    val scaleMs: Long
        get() = maxOf(p99Ms, maxMs, 1L)
}

data class DnsCountersUiState(
    val queriesTotal: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val failuresTotal: Long,
) {
    val cacheHitRatePercent: Int?
        get() = (cacheHits + cacheMisses).takeIf { it > 0L }?.let { lookups -> ((cacheHits * 100L) / lookups).toInt() }
}

data class ConnectionHealthRowUiState(
    val destinationClass: ConnectionHealthDestinationClass,
    val activeStrategy: String? = null,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val attributedCount: Long = 0L,
    val lastUpdatedAt: Long = 0L,
) {
    val totalCount: Long
        get() = successCount + failureCount

    val successRatePercent: Int?
        get() = totalCount.takeIf { it > 0L }?.let { total -> ((successCount * 100L) / total).toInt() }
}

@HiltViewModel
class ConnectionHealthViewModel
    @Inject
    constructor(
        repository: ConnectionHealthRepository,
        runtimeTelemetryInsightsRepository: RuntimeTelemetryInsightsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<ConnectionHealthUiState> =
            combine(
                repository.snapshots,
                runtimeTelemetryInsightsRepository.insights,
            ) { snapshot, insights -> toUiState(snapshot, insights) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = ConnectionHealthUiState(),
                )
    }

private fun toUiState(
    snapshot: ConnectionHealthSnapshot,
    insights: RuntimeTelemetryInsights,
): ConnectionHealthUiState =
    ConnectionHealthUiState(
        rows = snapshot.buckets.map(ConnectionHealthBucket::toRowUiState),
        qualityLossPercent = snapshot.quality?.lossPct?.toInt(),
        qualityRttP50Ms = snapshot.quality?.rttP50Ms,
        latencyDistributions = insights.latencyDistributions.toUiStates(),
        dnsCounters = insights.dnsCounters?.toUiState(),
        observedAt = snapshot.observedAt,
    )

private fun LatencyDistributions?.toUiStates(): List<LatencyDistributionUiState> {
    if (this == null) return emptyList()
    return buildList {
        dnsResolution?.toUiState(LatencyDistributionKind.DnsResolution)?.let(::add)
        tcpConnect?.toUiState(LatencyDistributionKind.TcpConnect)?.let(::add)
        tlsHandshake?.toUiState(LatencyDistributionKind.TlsHandshake)?.let(::add)
    }
}

private fun LatencyPercentiles.toUiState(kind: LatencyDistributionKind): LatencyDistributionUiState? =
    takeIf { count > 0L }?.let {
        LatencyDistributionUiState(
            kind = kind,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            minMs = min,
            maxMs = max,
            count = count,
        )
    }

private fun DnsCounterSnapshot.toUiState(): DnsCountersUiState =
    DnsCountersUiState(
        queriesTotal = queriesTotal,
        cacheHits = cacheHits,
        cacheMisses = cacheMisses,
        failuresTotal = failuresTotal,
    )

private fun ConnectionHealthBucket.toRowUiState(): ConnectionHealthRowUiState =
    ConnectionHealthRowUiState(
        destinationClass = destinationClass,
        activeStrategy = activeStrategy,
        successCount = successCount,
        failureCount = failureCount,
        attributedCount = attributedCount,
        lastUpdatedAt = lastUpdatedAt,
    )

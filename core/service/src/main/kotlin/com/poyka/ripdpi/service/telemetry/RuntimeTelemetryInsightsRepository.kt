package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.LatencyDistributions
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregate DNS resolver counters projected from the live [NativeRuntimeSnapshot]. Only
 * privacy-safe aggregate totals are carried — never the last DNS host or error string.
 */
data class DnsCounterSnapshot(
    val queriesTotal: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val failuresTotal: Long,
) {
    val hasData: Boolean
        get() = queriesTotal > 0L || failuresTotal > 0L || cacheHits > 0L || cacheMisses > 0L
}

/**
 * Latency-distribution histograms and DNS resolver counters that the native runtime already
 * records on every [NativeRuntimeSnapshot] but that the diagnostics UI historically discarded.
 */
data class RuntimeTelemetryInsights(
    val latencyDistributions: LatencyDistributions? = null,
    val dnsCounters: DnsCounterSnapshot? = null,
) {
    val hasData: Boolean
        get() = latencyDistributions != null || dnsCounters != null
}

interface RuntimeTelemetryInsightsRepository {
    val insights: StateFlow<RuntimeTelemetryInsights>
}

/**
 * Projects [RuntimeTelemetryInsights] out of the shared service telemetry flow. Reuses the same
 * 1 Hz [ServiceStateStore.telemetry] feed that drives connection-health, so it adds no extra
 * polling cost.
 */
@Singleton
class DefaultRuntimeTelemetryInsightsRepository
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : RuntimeTelemetryInsightsRepository {
        private val mutableInsights = MutableStateFlow(RuntimeTelemetryInsights())
        override val insights: StateFlow<RuntimeTelemetryInsights> = mutableInsights.asStateFlow()

        init {
            applicationScope.launch {
                serviceStateStore.telemetry.collect { telemetry ->
                    mutableInsights.value = project(telemetry)
                }
            }
        }

        private fun project(telemetry: ServiceTelemetrySnapshot): RuntimeTelemetryInsights {
            val proxy = telemetry.proxyTelemetry
            val tunnel = telemetry.tunnelTelemetry
            // Resolver counters + histograms live on whichever engine snapshot actually ran the
            // resolver: prefer the one reporting queries, falling back to the proxy snapshot.
            val counterSource = if (tunnel.dnsQueriesTotal >= proxy.dnsQueriesTotal) tunnel else proxy
            val counters =
                DnsCounterSnapshot(
                    queriesTotal = counterSource.dnsQueriesTotal,
                    cacheHits = counterSource.dnsCacheHits,
                    cacheMisses = counterSource.dnsCacheMisses,
                    failuresTotal = counterSource.dnsFailuresTotal,
                ).takeIf { it.hasData }
            return RuntimeTelemetryInsights(
                latencyDistributions = proxy.latencyDistributions ?: tunnel.latencyDistributions,
                dnsCounters = counters,
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeTelemetryInsightsRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindRuntimeTelemetryInsightsRepository(
        repository: DefaultRuntimeTelemetryInsightsRepository,
    ): RuntimeTelemetryInsightsRepository
}

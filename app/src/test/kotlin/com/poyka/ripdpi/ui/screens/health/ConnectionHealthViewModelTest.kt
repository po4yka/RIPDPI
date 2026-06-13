package com.poyka.ripdpi.ui.screens.health

import app.cash.turbine.test
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.LatencyDistributions
import com.poyka.ripdpi.data.LatencyPercentiles
import com.poyka.ripdpi.service.telemetry.DnsCounterSnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryInsights
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryInsightsRepository
import com.poyka.ripdpi.services.ConnectionHealthBucket
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.services.ConnectionHealthRepository
import com.poyka.ripdpi.services.ConnectionHealthSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHealthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `ui state maps seeded repository rates and quality`() =
        runTest {
            val repository =
                FakeConnectionHealthRepository(
                    ConnectionHealthSnapshot(
                        buckets =
                            listOf(
                                ConnectionHealthBucket(
                                    destinationClass = ConnectionHealthDestinationClass.YOUTUBE,
                                    activeStrategy = "quic:sni_split",
                                    successCount = 9,
                                    failureCount = 1,
                                    attributedCount = 4,
                                    lastUpdatedAt = 1_000L,
                                ),
                            ),
                        quality = ConnectionQualitySnapshot(lossPct = 10f, rttP50Ms = 55, sampleCount = 9),
                        observedAt = 1_000L,
                    ),
                )
            val viewModel = ConnectionHealthViewModel(repository, FakeRuntimeTelemetryInsightsRepository())

            viewModel.uiState.test {
                val state = awaitItem()
                val youtube = state.rows.single()
                assertEquals(ConnectionHealthDestinationClass.YOUTUBE, youtube.destinationClass)
                assertEquals(90, youtube.successRatePercent)
                assertEquals("quic:sni_split", youtube.activeStrategy)
                assertEquals(10, state.qualityLossPercent)
                assertEquals(55L, state.qualityRttP50Ms)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ui state projects latency distributions and dns counters`() =
        runTest {
            val repository = FakeConnectionHealthRepository(ConnectionHealthSnapshot(observedAt = 2_000L))
            val insightsRepository =
                FakeRuntimeTelemetryInsightsRepository(
                    RuntimeTelemetryInsights(
                        latencyDistributions =
                            LatencyDistributions(
                                dnsResolution =
                                    LatencyPercentiles(p50 = 18, p95 = 44, p99 = 91, min = 6, max = 102, count = 312),
                                // tlsHandshake present but empty (count 0) must be filtered out.
                                tlsHandshake =
                                    LatencyPercentiles(p50 = 0, p95 = 0, p99 = 0, min = 0, max = 0, count = 0),
                            ),
                        dnsCounters =
                            DnsCounterSnapshot(
                                queriesTotal = 1_204,
                                cacheHits = 938,
                                cacheMisses = 266,
                                failuresTotal = 12,
                            ),
                    ),
                )
            val viewModel = ConnectionHealthViewModel(repository, insightsRepository)

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(1, state.latencyDistributions.size)
                val dns = state.latencyDistributions.single()
                assertEquals(LatencyDistributionKind.DnsResolution, dns.kind)
                assertEquals(91L, dns.p99Ms)
                assertEquals(102L, dns.scaleMs)
                assertEquals(77, state.dnsCounters?.cacheHitRatePercent)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private class FakeConnectionHealthRepository(
    initial: ConnectionHealthSnapshot,
) : ConnectionHealthRepository {
    override val snapshots: StateFlow<ConnectionHealthSnapshot> = MutableStateFlow(initial)
}

private class FakeRuntimeTelemetryInsightsRepository(
    initial: RuntimeTelemetryInsights = RuntimeTelemetryInsights(),
) : RuntimeTelemetryInsightsRepository {
    override val insights: StateFlow<RuntimeTelemetryInsights> = MutableStateFlow(initial)
}

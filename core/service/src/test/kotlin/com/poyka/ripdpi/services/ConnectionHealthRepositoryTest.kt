package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.DirectPathLearningEvent
import com.poyka.ripdpi.data.DirectPathLearningSignal
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionHealthRepositoryTest {
    @Test
    fun `accumulator folds direct path signals by destination class and strategy`() {
        val accumulator = ConnectionHealthAccumulator()

        val snapshot =
            accumulator.consume(
                telemetry =
                    ServiceTelemetrySnapshot(
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                directPathLearningSignals =
                                    listOf(
                                        DirectPathLearningSignal(
                                            authority = "www.youtube.com",
                                            ipSetDigest = "yt",
                                            event = DirectPathLearningEvent.QUIC_SUCCESS,
                                            strategyFamily = "quic:sni_split",
                                            capturedAt = 1_000L,
                                        ),
                                        DirectPathLearningSignal(
                                            authority = "web.telegram.org",
                                            ipSetDigest = "tg",
                                            event = DirectPathLearningEvent.ALL_IPS_FAILED,
                                            strategyFamily = "tcp:record_split",
                                            capturedAt = 1_100L,
                                        ),
                                    ),
                                capturedAt = 1_200L,
                            ),
                        runtimeFieldTelemetry = RuntimeFieldTelemetry(winningTcpStrategyFamily = "tcp:fallback"),
                        updatedAt = 1_200L,
                    ),
                isAttributed = { digest -> digest == "yt" },
            )

        val youtube = snapshot.buckets.single { it.destinationClass == ConnectionHealthDestinationClass.YOUTUBE }
        val telegram = snapshot.buckets.single { it.destinationClass == ConnectionHealthDestinationClass.TELEGRAM }
        assertEquals(1L, youtube.successCount)
        assertEquals(0L, youtube.failureCount)
        assertEquals(1L, youtube.attributedCount)
        assertEquals("quic:sni_split", youtube.activeStrategy)
        assertEquals(0L, telegram.successCount)
        assertEquals(1L, telegram.failureCount)
        assertEquals("tcp:record_split", telegram.activeStrategy)
    }

    @Test
    fun `global quality changes never invent destination events`() {
        val accumulator = ConnectionHealthAccumulator()
        listOf(1L to 50f, 20L to 12.5f, 20L to 12.5f, 0L to 100f, 2L to 0.25f).forEach { (count, loss) ->
            val snapshot =
                accumulator.consume(
                    ServiceTelemetrySnapshot(
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                lastHost = "vk.com",
                                connectionQuality = ConnectionQualitySnapshot(lossPct = loss, sampleCount = count),
                                capturedAt = 2_000L,
                            ),
                        updatedAt = 3_000L,
                    ),
                )
            assertEquals(0L, snapshot.buckets.sumOf { it.totalCount })
            assertEquals(loss, snapshot.quality?.lossPct)
            assertEquals(2_000L, snapshot.qualitySource?.capturedAt)
            assertEquals(ConnectionHealthObservationPolicy.WindowMillis, snapshot.observationWindowMillis)
            assertEquals(ConnectionHealthObservationPolicy.Limit, snapshot.observationLimit)
        }
    }

    @Test
    fun `republication of proxy batch through status and tunnel updates is counted once`() {
        val accumulator = ConnectionHealthAccumulator()
        val proxy =
            NativeRuntimeSnapshot(
                source = "proxy",
                capturedAt = 1_200L,
                directPathLearningSignals =
                    listOf(
                        DirectPathLearningSignal(
                            "youtube.com",
                            "yt",
                            DirectPathLearningEvent.QUIC_SUCCESS,
                            capturedAt = 1_000L,
                        ),
                    ),
            )
        val first = ServiceTelemetrySnapshot(proxyTelemetry = proxy, updatedAt = 1_200L)
        accumulator.consume(first)
        val repeated =
            accumulator.consume(
                first.copy(
                    status = com.poyka.ripdpi.data.AppStatus.Running,
                    proxyTelemetry = proxy.copy(strategyPackId = "new-selection"),
                    tunnelTelemetry = NativeRuntimeSnapshot(source = "tunnel", capturedAt = 2_000L),
                    updatedAt = 2_000L,
                ),
            )
        assertEquals(1L, repeated.buckets.sumOf { it.totalCount })
        assertEquals(
            1_000L,
            repeated.buckets
                .single {
                    it.destinationClass == ConnectionHealthDestinationClass.YOUTUBE
                }.lastUpdatedAt,
        )
    }

    @Test
    fun `empty proxy quality does not shadow observed tunnel quality`() {
        val snapshot =
            ConnectionHealthAccumulator().consume(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            connectionQuality = ConnectionQualitySnapshot(),
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            capturedAt = 4_000L,
                            connectionQuality =
                                ConnectionQualitySnapshot(
                                    sampleCount = 2L,
                                    lossPct = 0.9f,
                                    rttP50Ms = 17L,
                                ),
                        ),
                    updatedAt = 5_000L,
                ),
            )
        assertEquals(0.9f, snapshot.quality?.lossPct)
        assertEquals("tunnel", snapshot.qualitySource?.source)
        assertEquals(4_000L, snapshot.qualitySource?.capturedAt)
    }

    @Test
    fun `observed zero loss quality remains distinct from an empty default`() {
        val accumulator = ConnectionHealthAccumulator()
        val observed =
            accumulator.consume(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            connectionQuality = ConnectionQualitySnapshot(windowStartAtMs = 1_000L),
                        ),
                ),
            )
        assertEquals(0f, observed.quality?.lossPct)
        org.junit.Assert.assertTrue(observed.hasData)
        val empty =
            accumulator.consume(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            connectionQuality = ConnectionQualitySnapshot(),
                        ),
                ),
            )
        org.junit.Assert.assertFalse(empty.hasData)
        org.junit.Assert.assertNull(empty.quality)
    }

    @Test
    fun `destination event window includes boundary and caps retained observations`() {
        val accumulator = ConnectionHealthAccumulator()
        val now = ConnectionHealthObservationPolicy.WindowMillis + 1_000L
        val signals =
            (0..ConnectionHealthObservationPolicy.Limit).map { index ->
                DirectPathLearningSignal(
                    authority = "youtube.com",
                    ipSetDigest = "signal-$index",
                    event = DirectPathLearningEvent.QUIC_SUCCESS,
                    capturedAt = now,
                )
            }
        val snapshot =
            accumulator.consume(
                ServiceTelemetrySnapshot(
                    proxyTelemetry = NativeRuntimeSnapshot(source = "proxy", directPathLearningSignals = signals),
                    updatedAt = now,
                ),
            )
        assertEquals(ConnectionHealthObservationPolicy.Limit.toLong(), snapshot.buckets.sumOf { it.totalCount })
        val atBoundary =
            accumulator.consume(
                ServiceTelemetrySnapshot(
                    updatedAt =
                        now + ConnectionHealthObservationPolicy.WindowMillis,
                ),
            )
        assertEquals(ConnectionHealthObservationPolicy.Limit.toLong(), atBoundary.buckets.sumOf { it.totalCount })
        val expired =
            accumulator.consume(
                ServiceTelemetrySnapshot(
                    updatedAt =
                        now + ConnectionHealthObservationPolicy.WindowMillis + 1L,
                ),
            )
        assertEquals(0L, expired.buckets.sumOf { it.totalCount })
    }
}

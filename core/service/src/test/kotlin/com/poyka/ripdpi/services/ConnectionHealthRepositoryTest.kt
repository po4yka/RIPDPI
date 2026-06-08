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
    fun `accumulator projects proxy quality deltas into the current host bucket`() {
        val accumulator = ConnectionHealthAccumulator()

        val snapshot =
            accumulator.consume(
                telemetry =
                    ServiceTelemetrySnapshot(
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                lastHost = "vk.com",
                                connectionQuality =
                                    ConnectionQualitySnapshot(
                                        lossPct = 50f,
                                        rttP50Ms = 42,
                                        sampleCount = 1,
                                    ),
                                capturedAt = 2_000L,
                            ),
                        runtimeFieldTelemetry = RuntimeFieldTelemetry(winningTcpStrategyFamily = "tcp:split"),
                        updatedAt = 2_000L,
                    ),
            )

        val vk = snapshot.buckets.single { it.destinationClass == ConnectionHealthDestinationClass.VK }
        assertEquals(1L, vk.successCount)
        assertEquals(1L, vk.failureCount)
        assertEquals("tcp:split", vk.activeStrategy)
        assertEquals(50f, snapshot.quality?.lossPct)
    }
}

package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

class NativeWrapperBaselineCaptureTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun captureProxyWrapperBaseline() =
        runTest {
            val payload =
                WrapperBaselineReport(
                    version = 1,
                    iterations = IterationPlan(startIterations = 20, stopIterations = 20, pollIterations = 150),
                    startup =
                        measureRepeated(20) {
                            val bindings = FakeRipDpiProxyBindings()
                            val proxy = RipDpiProxy(bindings)
                            proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1200)))
                        },
                    shutdown =
                        measureRepeated(20) {
                            coroutineScope {
                                val blocker = CompletableDeferred<Unit>()
                                val startedSignal = CompletableDeferred<Long>()
                                val bindings =
                                    FakeRipDpiProxyBindings().apply {
                                        this.startedSignal = startedSignal
                                        startBlocker = blocker
                                        stopCompletesStartBlocker = true
                                    }
                                val proxy = RipDpiProxy(bindings)
                                val start =
                                    async {
                                        proxy.startProxy(
                                            RipDpiProxyUIPreferences(
                                                listen = RipDpiListenConfig(port = 1201),
                                            ),
                                        )
                                    }
                                startedSignal.await()
                                proxy.stopProxy()
                                start.await()
                            }
                        },
                    pollTelemetry = measureActivePollTelemetry(),
                )

            assertTrue("startup baseline mean must be positive", payload.startup.meanNs > 0)
            assertTrue("shutdown baseline mean must be positive", payload.shutdown.meanNs > 0)
            assertTrue("poll baseline mean must be positive", payload.pollTelemetry.meanNs > 0)
            maybeWriteReport(payload)
        }

    private suspend fun measureRepeated(
        iterations: Int,
        block: suspend () -> Unit,
    ): NsSummary {
        repeat(5) { block() }
        val samples = LongArray(iterations)
        repeat(iterations) { index ->
            val startedAt = System.nanoTime()
            block()
            samples[index] = System.nanoTime() - startedAt
        }
        return NsSummary.from(samples)
    }

    private suspend fun measureActivePollTelemetry(): NsSummary =
        coroutineScope {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "proxy", state = "running", health = "healthy"),
                        )
                }
            val proxy = RipDpiProxy(bindings)
            val start =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1202)))
                }
            startedSignal.await()

            repeat(10) { proxy.pollTelemetry() }
            val samples = LongArray(150)
            repeat(samples.size) { index ->
                val startedAt = System.nanoTime()
                proxy.pollTelemetry()
                samples[index] = System.nanoTime() - startedAt
            }

            blocker.complete(Unit)
            start.await()
            NsSummary.from(samples)
        }

    private fun maybeWriteReport(payload: WrapperBaselineReport) {
        val outputPath =
            System
                .getProperty(BASELINE_OUTPUT_PROPERTY)
                ?.takeIf { it.isNotBlank() }
                ?: System
                    .getenv(BASELINE_OUTPUT_ENV)
                    ?.takeIf { it.isNotBlank() }
                ?: return
        val path = Path.of(outputPath)
        path.parent?.let(Files::createDirectories)
        path.toFile().writeText(json.encodeToString(payload))
    }

    @Serializable
    data class WrapperBaselineReport(
        val version: Int,
        val iterations: IterationPlan,
        val startup: NsSummary,
        val shutdown: NsSummary,
        val pollTelemetry: NsSummary,
    )

    @Serializable
    data class IterationPlan(
        val startIterations: Int,
        val stopIterations: Int,
        val pollIterations: Int,
    )

    @Serializable
    data class NsSummary(
        val meanNs: Long,
        val medianNs: Long,
        val p95Ns: Long,
        val minNs: Long,
        val maxNs: Long,
    ) {
        companion object {
            fun from(samples: LongArray): NsSummary {
                val sorted = samples.sorted()
                val mean = sorted.average().toLong()
                return NsSummary(
                    meanNs = mean,
                    medianNs = percentile(sorted, 0.5),
                    p95Ns = percentile(sorted, 0.95),
                    minNs = sorted.first(),
                    maxNs = sorted.last(),
                )
            }

            private fun percentile(
                sorted: List<Long>,
                fraction: Double,
            ): Long {
                val index = ceil((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
                return sorted[index]
            }
        }
    }

    private companion object {
        const val BASELINE_OUTPUT_PROPERTY = "ripdpi.baseline.output"
        const val BASELINE_OUTPUT_ENV = "RIPDPI_BASELINE_OUTPUT"
    }
}

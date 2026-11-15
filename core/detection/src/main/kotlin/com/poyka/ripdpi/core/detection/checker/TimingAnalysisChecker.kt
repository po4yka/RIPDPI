@file:Suppress("ReturnCount", "MagicNumber", "LongMethod")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

object TimingAnalysisChecker {
    private val PROBE_TARGETS =
        listOf(
            "google.com" to 443,
            "cloudflare.com" to 443,
            "yandex.ru" to 443,
        )
    private const val SAMPLES_PER_TARGET = 3
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val LOW_VARIANCE_THRESHOLD = 0.15
    private const val CONSISTENT_OFFSET_THRESHOLD_MS = 30.0
    private const val MIN_SAMPLES_REQUIRED = 4

    data class TimingSnapshot(
        val target: String,
        val rttMs: List<Long>,
        val resolvedAddress: String? = null,
    )

    suspend fun check(dispatchers: AppCoroutineDispatchers): CategoryResult = analyze(collectTimingSamples(dispatchers))

    internal fun analyze(snapshots: List<TimingSnapshot>): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        var needsReview = false

        if (snapshots.isEmpty()) {
            findings.add(Finding("Timing analysis: no targets reachable"))
            return CategoryResult(
                name = "Timing Analysis",
                detected = false,
                findings = findings,
            )
        }

        val allRtts = snapshots.flatMap { it.rttMs }
        if (allRtts.size < MIN_SAMPLES_REQUIRED) {
            findings.add(Finding("Timing analysis: insufficient samples (${allRtts.size})"))
            return CategoryResult(
                name = "Timing Analysis",
                detected = false,
                findings = findings,
            )
        }

        val mean = allRtts.average()
        val stdDev = standardDeviation(allRtts)
        val cv = if (mean > 0) stdDev / mean else 0.0

        findings.add(Finding("TCP connect latency mean: ${"%.1f".format(mean)}ms across ${allRtts.size} samples"))
        findings.add(Finding("TCP connect latency std dev: ${"%.1f".format(stdDev)}ms (CV: ${"%.2f".format(cv)})"))

        for (snapshot in snapshots) {
            val targetMean = snapshot.rttMs.average()
            val endpoint = snapshot.resolvedAddress?.let { "${snapshot.target} [$it]" } ?: snapshot.target
            findings.add(
                Finding(
                    "  $endpoint: ${"%.0f".format(targetMean)}ms avg " +
                        "(${snapshot.rttMs.joinToString(", ")}ms)",
                ),
            )
        }

        if (cv < LOW_VARIANCE_THRESHOLD && mean > CONSISTENT_OFFSET_THRESHOLD_MS) {
            needsReview = true
            findings.add(
                Finding(
                    description =
                        "Low TCP connect latency variation (CV=${"%.2f".format(cv)}) with elevated baseline " +
                            "(${"%.0f".format(mean)}ms); the cause is not established",
                    needsReview = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
            evidence.add(
                networkTimingEvidence(
                    confidence = EvidenceConfidence.LOW,
                    description =
                        "TCP connect latency needs review " +
                            "(CV=${"%.2f".format(cv)}, mean=${"%.0f".format(mean)}ms)",
                ),
            )
        }

        val crossTargetVariance = analyzeInterTargetConsistency(snapshots)
        if (crossTargetVariance != null && crossTargetVariance < LOW_VARIANCE_THRESHOLD) {
            needsReview = true
            findings.add(
                Finding(
                    description =
                        "Cross-target TCP connect latency consistency (${"%.2f".format(crossTargetVariance)}); " +
                            "the cause is not established",
                    needsReview = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
            evidence.add(
                networkTimingEvidence(
                    confidence = EvidenceConfidence.LOW,
                    description = "Multiple targets show similar TCP connect latency",
                ),
            )
        }

        if (mean > 200) {
            needsReview = true
            findings.add(
                Finding(
                    description =
                        "High TCP connect latency (${"%.0f".format(mean)}ms); " +
                            "distance or congestion may affect this measurement",
                    needsReview = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
        }

        if (!needsReview) {
            findings.add(Finding("TCP connect latency: no timing anomaly observed"))
        }

        return CategoryResult(
            name = "Timing Analysis",
            detected = false,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    internal fun networkTimingEvidence(
        confidence: EvidenceConfidence,
        description: String,
    ): EvidenceItem =
        EvidenceItem(
            source = EvidenceSource.NETWORK_CAPABILITIES,
            scope = DetectionScope.NETWORK_OBSERVATION,
            detected = true,
            confidence = confidence,
            description = description,
        )

    private suspend fun collectTimingSamples(dispatchers: AppCoroutineDispatchers): List<TimingSnapshot> =
        coroutineScope {
            PROBE_TARGETS
                .map { (host, port) ->
                    async {
                        collectTargetTiming(dispatchers, host, port)
                    }
                }.mapNotNull { it.await() }
        }

    internal suspend fun collectTargetTiming(
        dispatchers: AppCoroutineDispatchers,
        host: String,
        port: Int,
        resolveAddresses: suspend (String) -> List<InetAddress>? = { resolveDetectionAddresses(it) },
    ): TimingSnapshot? {
        return try {
            val address = resolveAddresses(host)?.firstOrNull() ?: return null
            val rtts = mutableListOf<Long>()
            repeat(SAMPLES_PER_TARGET) {
                measureConnectLatency(dispatchers, address, port)?.let { rtts.add(it) }
            }
            if (rtts.isEmpty()) null else TimingSnapshot(host, rtts, address.hostAddress)
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun measureConnectLatency(
        dispatchers: AppCoroutineDispatchers,
        address: InetAddress,
        port: Int,
    ): Long? =
        withContext(dispatchers.io) {
            try {
                Socket().use { socket ->
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { runCatching { socket.close() } }
                        if (continuation.isActive) {
                            try {
                                val endpoint = InetSocketAddress(address, port)
                                val start = System.nanoTime()
                                socket.connect(endpoint, CONNECT_TIMEOUT_MS)
                                val elapsed = (System.nanoTime() - start) / 1_000_000
                                continuation.resume(elapsed)
                            } catch (error: IOException) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                null
            }
        }

    private fun standardDeviation(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    private fun analyzeInterTargetConsistency(snapshots: List<TimingSnapshot>): Double? {
        if (snapshots.size < 2) return null
        val means = snapshots.map { it.rttMs.average() }
        val overallMean = means.average()
        if (overallMean <= 0) return null
        val stdDev = sqrt(means.sumOf { (it - overallMean) * (it - overallMean) } / (means.size - 1))
        return stdDev / overallMean
    }
}

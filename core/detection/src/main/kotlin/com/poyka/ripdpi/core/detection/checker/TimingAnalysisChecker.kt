package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
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
    )

    suspend fun check(dispatchers: AppCoroutineDispatchers): CategoryResult =
        coroutineScope {
            val findings = mutableListOf<Finding>()
            val evidence = mutableListOf<EvidenceItem>()
            var detected = false
            var needsReview = false

            val snapshots = collectTimingSamples(dispatchers)

            if (snapshots.isEmpty()) {
                findings.add(Finding("Timing analysis: no targets reachable"))
                return@coroutineScope CategoryResult(
                    name = "Timing Analysis",
                    detected = false,
                    findings = findings,
                )
            }

            val allRtts = snapshots.flatMap { it.rttMs }
            if (allRtts.size < MIN_SAMPLES_REQUIRED) {
                findings.add(Finding("Timing analysis: insufficient samples (${allRtts.size})"))
                return@coroutineScope CategoryResult(
                    name = "Timing Analysis",
                    detected = false,
                    findings = findings,
                )
            }

            val mean = allRtts.average()
            val stdDev = standardDeviation(allRtts)
            val cv = if (mean > 0) stdDev / mean else 0.0

            findings.add(Finding("RTT mean: ${"%.1f".format(mean)}ms across ${allRtts.size} samples"))
            findings.add(Finding("RTT std dev: ${"%.1f".format(stdDev)}ms (CV: ${"%.2f".format(cv)})"))

            for (snapshot in snapshots) {
                val targetMean = snapshot.rttMs.average()
                findings.add(
                    Finding(
                        "  ${snapshot.target}: ${"%.0f".format(targetMean)}ms avg " +
                            "(${snapshot.rttMs.joinToString(", ")}ms)",
                    ),
                )
            }

            if (cv < LOW_VARIANCE_THRESHOLD && mean > CONSISTENT_OFFSET_THRESHOLD_MS) {
                detected = true
                findings.add(
                    Finding(
                        description =
                            "Low RTT variance (CV=${"%.2f".format(cv)}) with elevated baseline " +
                                "(${"%.0f".format(mean)}ms) - consistent with VPN tunnel overhead",
                        detected = true,
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        confidence = EvidenceConfidence.MEDIUM,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        detected = true,
                        confidence = EvidenceConfidence.MEDIUM,
                        description =
                            "Connection timing shows consistent tunnel overhead pattern " +
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
                            "Cross-target RTT consistency (${"%.2f".format(crossTargetVariance)}) " +
                                "- targets show similar latency (tunnel routing)",
                        needsReview = true,
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        confidence = EvidenceConfidence.LOW,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        detected = true,
                        confidence = EvidenceConfidence.LOW,
                        description = "Multiple targets show unusually consistent latency",
                    ),
                )
            }

            if (mean > 200) {
                needsReview = true
                findings.add(
                    Finding(
                        description =
                            "High baseline RTT (${"%.0f".format(mean)}ms) - may indicate " +
                                "multi-hop tunneling",
                        needsReview = true,
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        confidence = EvidenceConfidence.LOW,
                    ),
                )
            }

            if (!detected && !needsReview) {
                findings.add(Finding("Timing pattern: normal variance, no tunnel signature detected"))
            }

            CategoryResult(
                name = "Timing Analysis",
                detected = detected,
                findings = findings,
                needsReview = needsReview,
                evidence = evidence,
            )
        }

    private suspend fun collectTimingSamples(dispatchers: AppCoroutineDispatchers): List<TimingSnapshot> =
        coroutineScope {
            PROBE_TARGETS
                .map { (host, port) ->
                    async {
                        val rtts = mutableListOf<Long>()
                        repeat(SAMPLES_PER_TARGET) {
                            measureConnectRtt(dispatchers, host, port)?.let { rtts.add(it) }
                        }
                        if (rtts.isNotEmpty()) TimingSnapshot(host, rtts) else null
                    }
                }.mapNotNull { it.await() }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun measureConnectRtt(
        dispatchers: AppCoroutineDispatchers,
        host: String,
        port: Int,
    ): Long? =
        withContext(dispatchers.io) {
            try {
                val start = System.nanoTime()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                elapsed
            } catch (_: Exception) {
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

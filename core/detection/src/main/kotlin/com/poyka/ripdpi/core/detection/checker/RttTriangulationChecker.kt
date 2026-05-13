@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.RttTargetGroup
import com.poyka.ripdpi.core.detection.RttTargetProbeResult
import com.poyka.ripdpi.core.detection.RttTriangulationResult
import com.poyka.ripdpi.core.detection.RttTriangulationState
import java.util.Locale

object RttTriangulationChecker {
    const val RU_MEDIAN_THRESHOLD_MS = 80.0
    const val JITTER_THRESHOLD_MS = 60.0
    const val FOREIGN_CONGESTION_THRESHOLD_MS = 180.0

    val RU_TARGETS: List<String> =
        listOf(
            "yandex.ru",
            "mail.ru",
            "vk.com",
            "sberbank.ru",
            "gosuslugi.ru",
        )
    val FOREIGN_TARGETS: List<String> =
        listOf(
            "facebook.com",
            "github.com",
            "twitter.com",
            "reddit.com",
            "instagram.com",
        )

    private const val NAME = "RTT triangulation"
    private const val RUSSIA_ISO = "RU"

    suspend fun check(
        prober: PingProber,
        enabled: Boolean,
        homeCountryIso: String?,
    ): RttTriangulationResult {
        if (!enabled) {
            return skipped("RTT triangulation disabled")
        }
        if (!homeCountryIso.equals(RUSSIA_ISO, ignoreCase = true)) {
            val country = homeCountryIso?.uppercase(Locale.US) ?: "unknown"
            return skipped("RTT triangulation skipped: home country $country is not Russia")
        }

        val probes =
            RU_TARGETS.map { probeTarget(prober, it, RttTargetGroup.RU) } +
                FOREIGN_TARGETS.map { probeTarget(prober, it, RttTargetGroup.FOREIGN) }
        val ruMedian = probes.medianFor(RttTargetGroup.RU)
        val foreignMedian = probes.medianFor(RttTargetGroup.FOREIGN)
        val needsReview = ruMedian?.let { it > RU_MEDIAN_THRESHOLD_MS } == true
        val confidence =
            if (needsReview) {
                confidence(probes, foreignMedian)
            } else {
                null
            }

        val findings = buildFindings(probes, ruMedian, foreignMedian, confidence)
        val reviewDescription =
            ruMedian?.let { value ->
                "Russian target median RTT ${format(value)}ms exceeds ${format(RU_MEDIAN_THRESHOLD_MS)}ms"
            }
        val evidence =
            if (needsReview && confidence != null && reviewDescription != null) {
                listOf(
                    EvidenceItem(
                        source = EvidenceSource.RTT_TRIANGULATION,
                        detected = true,
                        confidence = confidence,
                        description = reviewDescription,
                    ),
                )
            } else {
                emptyList()
            }

        return RttTriangulationResult(
            state = if (needsReview) RttTriangulationState.NEEDS_REVIEW else RttTriangulationState.OK,
            category =
                CategoryResult(
                    name = NAME,
                    detected = false,
                    findings = findings,
                    needsReview = needsReview,
                    evidence = evidence,
                ),
            targetResults = probes,
            ruMedianRttMs = ruMedian,
            foreignMedianRttMs = foreignMedian,
        )
    }

    private suspend fun probeTarget(
        prober: PingProber,
        host: String,
        group: RttTargetGroup,
    ): RttTargetProbeResult {
        val probe = prober.ping(host)
        return RttTargetProbeResult(
            host = host,
            group = group,
            resolvedIpv4 = probe.resolvedIpv4,
            rttMs = probe.rttMs,
            jitterMs = probe.jitterMs,
            replied = probe.replied,
        )
    }

    private fun confidence(
        probes: List<RttTargetProbeResult>,
        foreignMedian: Double?,
    ): EvidenceConfidence {
        val highJitterTargets =
            probes.count {
                it.jitterMs?.let { jitter -> jitter > JITTER_THRESHOLD_MS } == true
            }
        val majorityHighJitter = highJitterTargets > probes.size / 2
        val foreignAlsoHigh = foreignMedian?.let { it > FOREIGN_CONGESTION_THRESHOLD_MS } == true
        return if (majorityHighJitter || foreignAlsoHigh) {
            EvidenceConfidence.LOW
        } else {
            EvidenceConfidence.MEDIUM
        }
    }

    private fun buildFindings(
        probes: List<RttTargetProbeResult>,
        ruMedian: Double?,
        foreignMedian: Double?,
        confidence: EvidenceConfidence?,
    ): List<Finding> {
        val findings =
            probes
                .map { probe ->
                    val address = probe.resolvedIpv4 ?: "unresolved"
                    val rtt = probe.rttMs?.let { " RTT ${format(it)}ms" } ?: " no RTT"
                    val jitter = probe.jitterMs?.let { " jitter ${format(it)}ms" }.orEmpty()
                    val status = if (probe.replied) "replied" else "failed"
                    Finding("${probe.group.label()} ${probe.host} $status via $address$rtt$jitter")
                }.toMutableList()

        findings.add(Finding("Russian median RTT: ${ruMedian?.let(::format) ?: "N/A"}ms"))
        findings.add(Finding("Foreign median RTT: ${foreignMedian?.let(::format) ?: "N/A"}ms"))

        if (confidence != null && ruMedian != null) {
            val description = "Russian median RTT ${format(ruMedian)}ms exceeds ${format(RU_MEDIAN_THRESHOLD_MS)}ms"
            findings.add(
                Finding(
                    description = description,
                    needsReview = true,
                    source = EvidenceSource.RTT_TRIANGULATION,
                    confidence = confidence,
                ),
            )
        } else {
            findings.add(Finding("RTT triangulation: no suspicious Russian target latency"))
        }

        return findings
    }

    private fun List<RttTargetProbeResult>.medianFor(group: RttTargetGroup): Double? =
        filter { it.group == group }
            .mapNotNull { it.rttMs }
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.median()

    private fun List<Double>.median(): Double {
        val middle = size / 2
        return if (size % 2 == 0) {
            (this[middle - 1] + this[middle]) / 2.0
        } else {
            this[middle]
        }
    }

    private fun skipped(description: String): RttTriangulationResult =
        RttTriangulationResult(
            state = RttTriangulationState.SKIPPED,
            category =
                CategoryResult(
                    name = NAME,
                    detected = false,
                    findings = listOf(Finding(description)),
                ),
        )

    private fun RttTargetGroup.label(): String =
        when (this) {
            RttTargetGroup.RU -> "RU"
            RttTargetGroup.FOREIGN -> "Foreign"
        }

    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
}

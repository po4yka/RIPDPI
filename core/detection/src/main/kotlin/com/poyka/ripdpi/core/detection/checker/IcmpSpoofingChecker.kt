@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IcmpSpoofingResult
import com.poyka.ripdpi.core.detection.IcmpSpoofingState
import com.poyka.ripdpi.core.detection.PingProbeFailure
import java.util.Locale

object IcmpSpoofingChecker {
    private const val NAME = "ICMP spoofing"
    private const val BLOCKED_TARGET = "instagram.com"
    private const val CONTROL_TARGET = "google.com"
    private const val LOW_CONTROL_RTT_MS = 10.0

    suspend fun check(
        prober: PingProber,
        homeRoutedRoaming: Boolean,
    ): IcmpSpoofingResult {
        if (homeRoutedRoaming) {
            return IcmpSpoofingResult(
                state = IcmpSpoofingState.SKIPPED,
                category =
                    CategoryResult(
                        name = NAME,
                        detected = false,
                        findings = listOf(Finding("ICMP spoofing check skipped for home-routed roaming")),
                        executionStatus = CheckExecutionStatus.SKIPPED,
                    ),
            )
        }

        val blockedProbe = prober.ping(BLOCKED_TARGET)
        val controlProbe = prober.ping(CONTROL_TARGET)
        val blockedTargetReplied = blockedProbe.replied
        val controlRttLow = controlProbe.rttMs?.let { it < LOW_CONTROL_RTT_MS } == true
        val complete = blockedProbe.replied && controlProbe.replied
        val failed =
            listOf(blockedProbe, controlProbe).any {
                it.failure in
                    setOf(PingProbeFailure.DNS_ERROR, PingProbeFailure.OS_ERROR, PingProbeFailure.UNSUPPORTED_PLATFORM)
            }
        val confidence =
            if (blockedTargetReplied && controlRttLow) {
                EvidenceConfidence.HIGH
            } else {
                EvidenceConfidence.MEDIUM
            }

        val findings = buildFindings(blockedProbe, controlProbe, blockedTargetReplied, controlRttLow, confidence)
        val needsReview = blockedTargetReplied || controlRttLow
        val evidence =
            if (needsReview) {
                listOf(
                    EvidenceItem(
                        source = EvidenceSource.ICMP_SPOOFING,
                        detected = true,
                        confidence = confidence,
                        description = reviewDescription(blockedTargetReplied, controlRttLow),
                    ),
                )
            } else {
                emptyList()
            }

        return IcmpSpoofingResult(
            state =
                when {
                    needsReview -> IcmpSpoofingState.NEEDS_REVIEW
                    !complete -> IcmpSpoofingState.INCONCLUSIVE
                    else -> IcmpSpoofingState.OK
                },
            category =
                CategoryResult(
                    name = NAME,
                    detected = false,
                    findings = findings,
                    needsReview = needsReview,
                    evidence = evidence,
                    executionStatus =
                        when {
                            complete -> CheckExecutionStatus.COMPLETED
                            failed -> CheckExecutionStatus.ERROR
                            else -> CheckExecutionStatus.INCONCLUSIVE
                        },
                ),
            blockedTargetAddress = blockedProbe.resolvedIpv4,
            blockedTargetRttMs = blockedProbe.rttMs,
            controlTargetAddress = controlProbe.resolvedIpv4,
            controlTargetRttMs = controlProbe.rttMs,
        )
    }

    private fun buildFindings(
        blockedProbe: PingProbeResult,
        controlProbe: PingProbeResult,
        blockedTargetReplied: Boolean,
        controlRttLow: Boolean,
        confidence: EvidenceConfidence,
    ): List<Finding> =
        buildList {
            add(probeSummary(blockedProbe))
            add(probeSummary(controlProbe))

            if (controlRttLow && controlProbe.rttMs != null) {
                add(Finding("$CONTROL_TARGET RTT ${formatRtt(controlProbe.rttMs)}ms below local spoof threshold"))
            }

            if (blockedTargetReplied || controlRttLow) {
                add(
                    Finding(
                        description = reviewDescription(blockedTargetReplied, controlRttLow),
                        needsReview = true,
                        source = EvidenceSource.ICMP_SPOOFING,
                        confidence = confidence,
                    ),
                )
            } else {
                add(Finding("ICMP spoofing: insufficient measurements"))
            }
        }

    private fun probeSummary(probe: PingProbeResult): Finding {
        val address = probe.resolvedIpv4 ?: "unresolved"
        val rtt = probe.rttMs?.let { " RTT ${formatRtt(it)}ms" }.orEmpty()
        val status = if (probe.replied) "replied" else "no measurement (${probe.failure ?: PingProbeFailure.NO_REPLY})"
        return Finding("${probe.host} $status via $address$rtt")
    }

    private fun reviewDescription(
        blockedTargetReplied: Boolean,
        controlRttLow: Boolean,
    ): String =
        when {
            blockedTargetReplied && controlRttLow -> {
                "$BLOCKED_TARGET replied and $CONTROL_TARGET RTT is below ${formatRtt(LOW_CONTROL_RTT_MS)}ms"
            }

            blockedTargetReplied -> {
                "$BLOCKED_TARGET replied to ICMP probe"
            }

            else -> {
                "$CONTROL_TARGET RTT is below ${formatRtt(LOW_CONTROL_RTT_MS)}ms"
            }
        }

    private fun formatRtt(rttMs: Double): String = String.format(Locale.US, "%.1f", rttMs)
}

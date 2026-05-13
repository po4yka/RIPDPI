@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IcmpSpoofingResult
import com.poyka.ripdpi.core.detection.IcmpSpoofingState
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

interface PingProber {
    suspend fun ping(host: String): PingProbeResult
}

data class PingProbeResult(
    val host: String,
    val resolvedIpv4: String?,
    val replied: Boolean,
    val rttMs: Double?,
    val jitterMs: Double? = null,
    val rawOutput: String? = null,
) {
    companion object {
        fun replied(
            host: String,
            resolvedIpv4: String,
            rttMs: Double,
            jitterMs: Double? = null,
            rawOutput: String? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                replied = true,
                rttMs = rttMs,
                jitterMs = jitterMs,
                rawOutput = rawOutput,
            )

        fun unreachable(
            host: String,
            resolvedIpv4: String?,
            rawOutput: String? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                replied = false,
                rttMs = null,
                rawOutput = rawOutput,
            )
    }
}

class SystemPingProber(
    private val dispatchers: AppCoroutineDispatchers,
    private val sampleCount: Int = 1,
    private val timeoutSeconds: Int = PING_TIMEOUT_SECONDS,
) : PingProber {
    override suspend fun ping(host: String): PingProbeResult =
        withContext(dispatchers.io) {
            val address =
                try {
                    InetAddress
                        .getAllByName(host)
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull()
                        ?.hostAddress
                } catch (_: Exception) {
                    null
                }

            if (address == null) {
                return@withContext PingProbeResult.unreachable(host, resolvedIpv4 = null)
            }

            runPing(host = host, address = address)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun runPing(
        host: String,
        address: String,
    ): PingProbeResult =
        try {
            val process =
                ProcessBuilder("ping", "-c", sampleCount.toString(), "-W", timeoutSeconds.toString(), address)
                    .redirectErrorStream(true)
                    .start()
            val finished = process.waitFor((timeoutSeconds * sampleCount).toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val rtt = parseRttMs(output)
            if (finished && process.exitValue() == 0 && rtt != null) {
                PingProbeResult.replied(host, address, rtt, jitterMs = parseJitterMs(output), rawOutput = output)
            } else {
                PingProbeResult.unreachable(host, address, output)
            }
        } catch (_: Exception) {
            PingProbeResult.unreachable(host, address)
        }

    internal fun parseRttMs(output: String): Double? {
        singleReplyRttRegex.find(output)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        summaryRttRegex.find(output)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    internal fun parseJitterMs(output: String): Double? =
        summaryRttRegex
            .find(output)
            ?.groupValues
            ?.getOrNull(2)
            ?.toDoubleOrNull()

    private companion object {
        private const val PING_TIMEOUT_SECONDS = 3
        private val singleReplyRttRegex = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")
        private val summaryRttRegex = Regex("""=\s*[0-9.]+/([0-9.]+)/[0-9.]+(?:/([0-9.]+))?\s*ms""")
    }
}

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
                    ),
            )
        }

        val blockedProbe = prober.ping(BLOCKED_TARGET)
        val controlProbe = prober.ping(CONTROL_TARGET)
        val blockedTargetReplied = blockedProbe.replied
        val controlRttLow = controlProbe.rttMs?.let { it < LOW_CONTROL_RTT_MS } == true
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
            state = if (needsReview) IcmpSpoofingState.NEEDS_REVIEW else IcmpSpoofingState.OK,
            category =
                CategoryResult(
                    name = NAME,
                    detected = false,
                    findings = findings,
                    needsReview = needsReview,
                    evidence = evidence,
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
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings.add(probeSummary(blockedProbe))
        findings.add(probeSummary(controlProbe))

        if (controlRttLow && controlProbe.rttMs != null) {
            findings.add(Finding("$CONTROL_TARGET RTT ${formatRtt(controlProbe.rttMs)}ms below local spoof threshold"))
        }

        if (blockedTargetReplied || controlRttLow) {
            findings.add(
                Finding(
                    description = reviewDescription(blockedTargetReplied, controlRttLow),
                    needsReview = true,
                    source = EvidenceSource.ICMP_SPOOFING,
                    confidence = confidence,
                ),
            )
        } else {
            findings.add(Finding("ICMP spoofing: no suspicious replies"))
        }

        return findings
    }

    private fun probeSummary(probe: PingProbeResult): Finding {
        val address = probe.resolvedIpv4 ?: "unresolved"
        val rtt = probe.rttMs?.let { " RTT ${formatRtt(it)}ms" }.orEmpty()
        val status = if (probe.replied) "replied" else "no reply"
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

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DirectModePolicySupportTest {
    @Test
    fun `dns poisoning selects encrypted dns mode while preserving typed classifier`() {
        val observations =
            collectDirectPathCapabilityObservations(
                reportWithResults(
                    ProbeResult(
                        probeType = "dns_integrity",
                        target = "Example.org",
                        outcome = "dns_sinkhole_substitution",
                        details =
                            listOf(
                                ProbeDetail("dnsClassification", "ECH_CAPABLE"),
                                ProbeDetail("dnsAnswerClass", "POISONED"),
                                ProbeDetail("dnsSelectedResolverRole", "secondary"),
                            ),
                    ),
                ),
            )

        val observation = observations.getValue("Example.org")

        assertEquals(DirectDnsClassification.ECH_CAPABLE, observation.dnsClassification)
        assertEquals(DnsMode.DOH_SECONDARY, observation.transportPolicy?.dnsMode)
    }

    @Test
    fun `clean dns result keeps system dns mode`() {
        val observations =
            collectDirectPathCapabilityObservations(
                reportWithResults(
                    ProbeResult(
                        probeType = "dns_integrity",
                        target = "example.org",
                        outcome = "dns_match",
                        details =
                            listOf(
                                ProbeDetail("dnsClassification", "CLEAN"),
                                ProbeDetail("dnsAnswerClass", "CLEAN"),
                                ProbeDetail("dnsSelectedResolverRole", "primary"),
                            ),
                    ),
                ),
            )

        val observation = observations.getValue("example.org")

        assertEquals(DirectDnsClassification.CLEAN, observation.dnsClassification)
        assertEquals(DnsMode.SYSTEM, observation.transportPolicy?.dnsMode)
    }

    @Test
    fun `all failed tls authority returns honest no direct solution verdict`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "strategy_https",
                    target = "example.org",
                    outcome = "tls_handshake_failed",
                    details = listOf(ProbeDetail("targetHost", "example.org")),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, verdict?.reasonCode)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, verdict?.transportClass)
    }

    @Test
    fun `all failed quic authority returns honest no direct solution verdict`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "strategy_quic",
                    target = "example.org",
                    outcome = "quic_error",
                    details = listOf(ProbeDetail("targetHost", "example.org")),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.QUIC_BLOCKED, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.QUIC_BLOCKED, verdict?.reasonCode)
        assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, verdict?.transportClass)
    }

    @Test
    fun `unreachable authority remains the ip block suspect path`() {
        val report =
            reportWithResults(
                ProbeResult(
                    probeType = "service_gateway",
                    target = "example.org",
                    outcome = "unreachable",
                    details = listOf(ProbeDetail("targetHost", "example.org")),
                ),
            )

        val observation = collectDirectPathCapabilityObservations(report).getValue("example.org")
        val verdict = deriveDirectModeVerdict(report)

        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, observation.transportPolicy?.outcome)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, observation.transportClass)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, observation.reasonCode)
        assertNotNull(observation.cooldownUntil)
        assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, verdict?.result)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, verdict?.reasonCode)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, verdict?.transportClass)
    }

    private fun reportWithResults(vararg results: ProbeResult): ScanReport =
        ScanReport(
            sessionId = "session",
            profileId = "profile",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "summary",
            results = results.toList(),
        )
}

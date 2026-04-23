package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DnsMode
import org.junit.Assert.assertEquals
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

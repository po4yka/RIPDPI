package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsDnsEvidenceBoundaryTest {
    @Test
    fun `NXDOMAIN difference does not claim deleted records`() {
        val diagnoses = classifyDns(DnsObservationStatus.NXDOMAIN_MISMATCH, 10, 100)
        assertEquals(
            "UDP DNS returned NXDOMAIN while encrypted DNS returned addresses",
            diagnoses.single { it.code == "dns_tampering" }.summary,
        )
    }

    @Test
    fun `low latency answer difference does not identify injection`() {
        val diagnoses = classifyDns(DnsObservationStatus.SINKHOLE_SUBSTITUTION, 3, 100)
        val finding = diagnoses.single { it.code == "dns_injection_suspected" }
        assertEquals("UDP DNS answers differed from encrypted DNS within 5ms", finding.summary)
        assertFalse(finding.recommendation.orEmpty().contains("DPI"))
        assertFalse(diagnoses.flatMap { it.evidence }.any { it == "mechanism=injection" })
    }

    @Test
    fun `slow DNS does not attribute delay to the provider`() {
        val finding =
            classifyDns(DnsObservationStatus.MATCH, 6_000, 100)
                .single { it.code == "dns_latency_anomaly" }
        assertEquals("UDP DNS latency exceeded the comparison threshold", finding.summary)
        assertFalse(finding.recommendation.orEmpty().contains("ISP"))
        assertFalse(finding.recommendation.orEmpty().contains("bypasses"))
    }

    private fun classifyDns(
        status: DnsObservationStatus,
        udpMs: Long,
        encryptedMs: Long,
    ): List<Diagnosis> =
        DiagnosticsFindingProjector().classify(
            listOf(
                ObservationFact(
                    kind = ObservationKind.DNS,
                    target = "target.example",
                    dns =
                        DnsObservationFact(
                            domain = "target.example",
                            status = status,
                            udpLatencyMs = udpMs,
                            encryptedLatencyMs = encryptedMs,
                        ),
                ),
            ),
        )
}

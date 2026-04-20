package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonScanCoordinatorTest {
    @Test
    fun `extractDomainOutcomes returns tls_ok for domain with successful strategy observation`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.STRATEGY,
                            target = "candidate_a \u00b7 blocked.example",
                            strategy =
                                StrategyObservationFact(
                                    candidateId = "candidate_a",
                                    protocol = StrategyProbeProtocol.HTTPS,
                                    status = StrategyProbeStatus.SUCCESS,
                                ),
                        ),
                    ),
            )

        val outcomes = ComparisonScanCoordinator.extractDomainOutcomes(report)
        assertEquals("tls_ok", outcomes["blocked.example"])
    }

    @Test
    fun `extractDomainOutcomes returns evidence when all strategy observations fail`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.STRATEGY,
                            target = "candidate_a \u00b7 discord.com",
                            strategy =
                                StrategyObservationFact(
                                    candidateId = "candidate_a",
                                    protocol = StrategyProbeProtocol.HTTPS,
                                    status = StrategyProbeStatus.FAILED,
                                ),
                            evidence = listOf("tls_handshake_failed"),
                        ),
                    ),
            )

        val outcomes = ComparisonScanCoordinator.extractDomainOutcomes(report)
        assertEquals("tls_handshake_failed", outcomes["discord.com"])
    }

    @Test
    fun `extractDomainOutcomes ignores non-HTTPS protocol observations`() {
        val report =
            minimalReport(
                observations =
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.STRATEGY,
                            target = "candidate_a \u00b7 example.com",
                            strategy =
                                StrategyObservationFact(
                                    candidateId = "candidate_a",
                                    protocol = StrategyProbeProtocol.QUIC,
                                    status = StrategyProbeStatus.SUCCESS,
                                ),
                        ),
                    ),
            )

        val outcomes = ComparisonScanCoordinator.extractDomainOutcomes(report)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `parseDomain extracts domain after middle dot separator`() {
        assertEquals("blocked.example", ComparisonScanCoordinator.parseDomain("candidate_a \u00b7 blocked.example"))
    }

    @Test
    fun `parseDomain returns null when separator is missing`() {
        assertEquals(null, ComparisonScanCoordinator.parseDomain("no-separator-here"))
    }

    @Test
    fun `isFailure returns true for known failure outcomes`() {
        assertTrue(ComparisonScanCoordinator.isFailure("tls_handshake_failed"))
        assertTrue(ComparisonScanCoordinator.isFailure("http_unreachable"))
        assertTrue(ComparisonScanCoordinator.isFailure("not_tested"))
    }

    @Test
    fun `isFailure returns false for success outcomes`() {
        assertFalse(ComparisonScanCoordinator.isFailure("tls_ok"))
        assertFalse(ComparisonScanCoordinator.isFailure("http_ok"))
    }

    @Test
    fun `isSuccess returns true for known success outcomes`() {
        assertTrue(ComparisonScanCoordinator.isSuccess("tls_ok"))
        assertTrue(ComparisonScanCoordinator.isSuccess("http_redirect"))
    }

    @Test
    fun `isSuccess returns false for failure outcomes`() {
        assertFalse(ComparisonScanCoordinator.isSuccess("tls_handshake_failed"))
        assertFalse(ComparisonScanCoordinator.isSuccess("unknown"))
    }

    private fun minimalReport(observations: List<ObservationFact> = emptyList()) =
        ScanReport(
            sessionId = "test-session",
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 0L,
            finishedAt = 1L,
            summary = "test",
            observations = observations,
        )
}

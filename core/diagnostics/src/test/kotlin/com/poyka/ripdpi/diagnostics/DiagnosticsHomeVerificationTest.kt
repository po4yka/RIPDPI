package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsHomeVerificationTest {
    @Test
    fun `verification confirms complete healthy application reachability`() =
        runTest {
            assertTrue(verificationOutcome(verificationReport()).success)
        }

    @Test
    fun `verification rejects interrupted reports even when TLS succeeded`() =
        runTest {
            listOf(ScanCompletionKind.PARTIAL_RESULTS, ScanCompletionKind.TERMINATED).forEach { completion ->
                assertFalse(
                    completion.name,
                    verificationOutcome(verificationReport().copy(completionKind = completion)).success,
                )
            }
            assertFalse(
                verificationOutcome(
                    verificationReport().copy(terminationReason = ScanTerminationReason.DEADLINE_EXCEEDED),
                ).success,
            )
            listOf("cancelled", "failed", "running").forEach { status ->
                assertFalse(status, verificationOutcome(verificationReport(), status = status).success)
            }
        }

    @Test
    fun `verification keeps adverse typed outcomes beside a successful target`() =
        runTest {
            listOf(
                "domain_reachability" to "tls_cert_invalid",
                "domain_reachability" to "http_blockpage",
                "dns_integrity" to "dns_sinkhole_substitution",
                "service_reachability" to "service_partial",
                "quic_reachability" to "quic_error",
            ).forEach { (type, outcome) ->
                val report = verificationReport()
                assertFalse(
                    outcome,
                    verificationOutcome(
                        report.copy(results = report.results + ProbeResult(type, "other.example", outcome)),
                    ).success,
                )
            }
        }

    @Test
    fun `verification does not accept unknown outcomes containing ok`() =
        runTest {
            assertFalse(
                verificationOutcome(
                    verificationReport().copy(results = listOf(ProbeResult("unknown", "example.com", "looks_ok"))),
                ).success,
            )
        }

    @Test
    fun `verification requires a VPN in-path report with valid network scope`() =
        runTest {
            assertFalse(verificationOutcome(verificationReport().copy(pathMode = ScanPathMode.RAW_PATH)).success)
            assertFalse(verificationOutcome(verificationReport(), serviceMode = "Proxy").success)
            assertFalse(
                verificationOutcome(
                    verificationReport().copy(
                        diagnoses = listOf(Diagnosis("network_scope_unverified", "Network changed", "warning")),
                    ),
                ).success,
            )
            assertFalse(verificationOutcome(verificationReport().copy(sessionId = "other-session")).success)
        }

    @Test
    fun `verification requires application evidence rather than environment or DNS alone`() =
        runTest {
            listOf(
                emptyList(),
                listOf(ProbeResult("network_environment", "network", "network_available")),
                listOf(ProbeResult("dns_integrity", "example.com", "dns_match")),
                listOf(ProbeResult("quic_reachability", "example.com", "quic_initial_response")),
            ).forEach { results ->
                assertFalse(verificationOutcome(verificationReport().copy(results = results)).success)
            }
        }

    private fun verificationReport(): ScanReport =
        ScanReport(
            sessionId = "verify-session",
            profileId = "default",
            pathMode = ScanPathMode.IN_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = "Verification complete",
            results = listOf(ProbeResult("domain_reachability", "example.com", "tls_ok")),
        )

    private fun verificationOutcome(
        report: ScanReport,
        status: String = "completed",
        serviceMode: String = "VPN",
    ): DiagnosticsHomeVerificationOutcome {
        val session =
            diagnosticsSession(
                id = "verify-session",
                profileId = "default",
                pathMode = report.pathMode.name,
                status = status,
                summary = report.summary,
            ).copy(serviceMode = serviceMode)
        return summarizeHomeVerification("verify-session", session, report)
    }
}

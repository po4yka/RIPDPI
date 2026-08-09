package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperFailureEnvelopesTest {
    @Test
    fun `collector maps typed failure facts without inferring errors from summary`() {
        assertEquals(
            DeveloperFailureEnvelopeEntry(
                stageKey = "default_connectivity",
                stageLabel = "default_connectivity",
                headline = "Typed probe failures recorded",
                summary = "6 typed failure facts; see referenced stage report observations.",
                tcpErrors =
                    listOf(
                        "stages/default_connectivity/report.json#/observations/1/tcp/status=CONNECT_FAILED",
                        "stages/default_connectivity/report.json#/observations/4/tcp/status=BLOCKED16_KB",
                    ),
                tlsErrors =
                    listOf(
                        "stages/default_connectivity/report.json#/observations/2/domain/tls13Status=HANDSHAKE_FAILED",
                    ),
                dnsErrors =
                    listOf("stages/default_connectivity/report.json#/observations/0/dns/status=NXDOMAIN_MISMATCH"),
                httpErrors =
                    listOf("stages/default_connectivity/report.json#/observations/2/domain/httpStatus=BLOCKPAGE"),
                quicErrors =
                    listOf("stages/default_connectivity/report.json#/observations/3/quic/status=ERROR"),
            ),
            buildDeveloperFailureEnvelopes(failureContext()).single(),
        )
    }

    private fun failureContext() =
        DeveloperAnalyticsContext(
            archiveCreatedAtMs = 1,
            archiveFileName = "ripdpi-analysis.zip",
            homeCompositeOutcome =
                DiagnosticsHomeCompositeOutcome(
                    runId = "home-run",
                    actionable = false,
                    headline = "Analysis incomplete",
                    summary = "One stage failed.",
                    stageSummaries =
                        listOf(
                            DiagnosticsHomeCompositeStageSummary(
                                stageKey = "default_connectivity",
                                stageLabel = "Default connectivity",
                                profileId = "default",
                                pathMode = ScanPathMode.RAW_PATH,
                                sessionId = "session-1",
                                status = DiagnosticsHomeCompositeStageStatus.FAILED,
                                headline = "Connectivity failed",
                                summary = "HTTP 500 TLS DNS RST reset refused timed out",
                            ),
                        ),
                    failedStageCount = 1,
                ),
            failureEnvelopes = listOf(DeveloperFailureEnvelopeEvidence("default_connectivity", typedFacts())),
        )

    private fun typedFacts() =
        listOf(
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.TCP,
                1,
                DeveloperFailureFactField.TCP_STATUS,
                TcpProbeStatus.CONNECT_FAILED,
            ),
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.TLS,
                2,
                DeveloperFailureFactField.DOMAIN_TLS13_STATUS,
                TlsProbeStatus.HANDSHAKE_FAILED,
            ),
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.DNS,
                0,
                DeveloperFailureFactField.DNS_STATUS,
                DnsObservationStatus.NXDOMAIN_MISMATCH,
            ),
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.HTTP,
                2,
                DeveloperFailureFactField.DOMAIN_HTTP_STATUS,
                HttpProbeStatus.BLOCKPAGE,
            ),
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.QUIC,
                3,
                DeveloperFailureFactField.QUIC_STATUS,
                QuicProbeStatus.ERROR,
            ),
            DeveloperFailureFactEvidence(
                DeveloperFailureCategory.TCP,
                4,
                DeveloperFailureFactField.TCP_STATUS,
                TcpProbeStatus.BLOCKED_16KB,
            ),
        )
}

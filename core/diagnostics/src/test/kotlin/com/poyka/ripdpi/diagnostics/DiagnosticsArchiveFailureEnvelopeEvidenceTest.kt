package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.buildDeveloperFailureEnvelopeEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticsArchiveFailureEnvelopeEvidenceTest {
    @Test
    fun `typed failure fact rejects status from another field`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperFailureFactEvidence(
                category = DeveloperFailureCategory.DNS,
                observationIndex = 0,
                field = DeveloperFailureFactField.DNS_STATUS,
                value = TcpProbeStatus.CONNECT_FAILED,
            )
        }
    }

    @Test
    fun `typed failure fact rejects colliding name from another enum type`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperFailureFactEvidence(
                category = DeveloperFailureCategory.QUIC,
                observationIndex = 0,
                field = DeveloperFailureFactField.QUIC_STATUS,
                value = TcpProbeStatus.ERROR,
            )
        }
    }

    @Test
    fun `failed stage envelope is built from typed observation facts`() {
        val evidence = requireNotNull(buildDeveloperFailureEnvelopeEvidence(failedStageSelection()))

        assertEquals(expectedFacts(), evidence.facts)
    }

    private fun expectedFacts() =
        listOf(
            failureFact(
                DeveloperFailureCategory.DNS,
                0,
                DeveloperFailureFactField.DNS_STATUS,
                DnsObservationStatus.NXDOMAIN_MISMATCH,
            ),
            failureFact(
                DeveloperFailureCategory.TCP,
                1,
                DeveloperFailureFactField.TCP_STATUS,
                TcpProbeStatus.CONNECT_FAILED,
            ),
            failureFact(
                DeveloperFailureCategory.TCP,
                2,
                DeveloperFailureFactField.DOMAIN_TRANSPORT_FAILURE,
                TransportFailureKind.RESET,
            ),
            failureFact(
                DeveloperFailureCategory.TLS,
                2,
                DeveloperFailureFactField.DOMAIN_TLS13_STATUS,
                TlsProbeStatus.HANDSHAKE_FAILED,
            ),
            failureFact(
                DeveloperFailureCategory.HTTP,
                2,
                DeveloperFailureFactField.DOMAIN_HTTP_STATUS,
                HttpProbeStatus.BLOCKPAGE,
            ),
            failureFact(
                DeveloperFailureCategory.QUIC,
                3,
                DeveloperFailureFactField.QUIC_STATUS,
                QuicProbeStatus.ERROR,
            ),
            failureFact(
                DeveloperFailureCategory.QUIC,
                3,
                DeveloperFailureFactField.QUIC_TRANSPORT_FAILURE,
                TransportFailureKind.TIMEOUT,
            ),
            failureFact(
                DeveloperFailureCategory.HTTP,
                4,
                DeveloperFailureFactField.STRATEGY_STATUS,
                StrategyProbeStatus.FAILED,
            ),
            failureFact(
                DeveloperFailureCategory.QUIC,
                5,
                DeveloperFailureFactField.SERVICE_QUIC_STATUS,
                QuicProbeStatus.ERROR,
            ),
            failureFact(
                DeveloperFailureCategory.QUIC,
                5,
                DeveloperFailureFactField.SERVICE_QUIC_FAILURE,
                TransportFailureKind.CLOSE,
            ),
        )

    private fun failedStageSelection() =
        DiagnosticsArchiveCompositeStageSelection(
            stageSummary =
                DiagnosticsHomeCompositeStageSummary(
                    stageKey = "default_connectivity",
                    stageLabel = "Default connectivity",
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH,
                    sessionId = "session-1",
                    status = DiagnosticsHomeCompositeStageStatus.FAILED,
                    headline = "Connectivity failed",
                    summary = "Summary intentionally contains no protocol details.",
                ),
            session = null,
            report =
                EngineScanReportWire(
                    sessionId = "session-1",
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 100,
                    finishedAt = 200,
                    summary = "Native report",
                    observations =
                        listOf(
                            failedDnsObservation(),
                            ObservationFact(
                                kind = ObservationKind.TCP,
                                target = "tcp.example",
                                tcp = TcpObservationFact("fixture", TcpProbeStatus.CONNECT_FAILED),
                            ),
                            ObservationFact(
                                kind = ObservationKind.DOMAIN,
                                target = "web.example",
                                domain =
                                    DomainObservationFact(
                                        host = "web.example",
                                        httpStatus = HttpProbeStatus.BLOCKPAGE,
                                        tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                        transportFailure = TransportFailureKind.RESET,
                                    ),
                            ),
                            ObservationFact(
                                kind = ObservationKind.QUIC,
                                target = "quic.example",
                                quic =
                                    QuicObservationFact(
                                        host = "quic.example",
                                        status = QuicProbeStatus.ERROR,
                                        transportFailure = TransportFailureKind.TIMEOUT,
                                    ),
                            ),
                            ObservationFact(
                                kind = ObservationKind.STRATEGY,
                                target = "strategy.example",
                                strategy =
                                    StrategyObservationFact(
                                        protocol = StrategyProbeProtocol.HTTPS,
                                        status = StrategyProbeStatus.FAILED,
                                    ),
                            ),
                            ObservationFact(
                                kind = ObservationKind.SERVICE,
                                target = "service.example",
                                service =
                                    ServiceObservationFact(
                                        service = "fixture",
                                        quicStatus = QuicProbeStatus.ERROR,
                                        quicFailure = TransportFailureKind.CLOSE,
                                    ),
                            ),
                            healthyDomainObservation(),
                        ),
                ),
            results = emptyList(),
            snapshots = emptyList(),
            contexts = emptyList(),
            events = emptyList(),
        )

    private fun failureFact(
        category: DeveloperFailureCategory,
        observationIndex: Int,
        field: DeveloperFailureFactField,
        value: Enum<*>,
    ) = DeveloperFailureFactEvidence(category, observationIndex, field, value)

    private fun failedDnsObservation() =
        ObservationFact(
            kind = ObservationKind.DNS,
            target = "dns.example",
            dns =
                DnsObservationFact(
                    domain = "dns.example",
                    status = DnsObservationStatus.NXDOMAIN_MISMATCH,
                ),
        )

    private fun healthyDomainObservation() =
        ObservationFact(
            kind = ObservationKind.DOMAIN,
            target = "healthy.example",
            domain =
                DomainObservationFact(
                    host = "healthy.example",
                    httpStatus = HttpProbeStatus.OK,
                    tls13Status = TlsProbeStatus.OK,
                    transportFailure = TransportFailureKind.NONE,
                ),
        )
}

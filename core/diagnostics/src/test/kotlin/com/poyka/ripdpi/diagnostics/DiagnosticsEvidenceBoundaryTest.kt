package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEvidenceBoundaryTest {
    @Test
    fun `TLS controls do not validate DNS QUIC or HTTP findings`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "control.example",
                        domain =
                            DomainObservationFact(
                                host = "control.example",
                                isControl = true,
                                tls13Status = TlsProbeStatus.OK,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "target.example",
                        domain = DomainObservationFact(host = "target.example", httpStatus = HttpProbeStatus.BLOCKPAGE),
                    ),
                    ObservationFact(
                        kind = ObservationKind.QUIC,
                        target = "target.example",
                        quic = QuicObservationFact(host = "target.example", status = QuicProbeStatus.ERROR),
                    ),
                    ObservationFact(
                        kind = ObservationKind.DNS,
                        target = "target.example",
                        dns =
                            DnsObservationFact(
                                domain = "target.example",
                                status = DnsObservationStatus.SINKHOLE_SUBSTITUTION,
                            ),
                    ),
                ),
            )
        assertTrue(diagnoses.isNotEmpty())
        diagnoses.forEach { assertNull(it.controlValidated) }
    }

    @Test
    fun `HTTP controls validate HTTP findings independently of TLS`() {
        for ((status, expected) in listOf(HttpProbeStatus.OK to true, HttpProbeStatus.UNREACHABLE to false)) {
            val diagnoses =
                DiagnosticsFindingProjector().classify(
                    listOf(
                        ObservationFact(
                            kind = ObservationKind.DOMAIN,
                            target = "control.example",
                            domain =
                                DomainObservationFact(
                                    host = "control.example",
                                    isControl = true,
                                    httpStatus = status,
                                ),
                        ),
                        ObservationFact(
                            kind = ObservationKind.DOMAIN,
                            target = "target.example",
                            domain =
                                DomainObservationFact(
                                    host = "target.example",
                                    httpStatus = HttpProbeStatus.BLOCKPAGE,
                                    transportFailure = TransportFailureKind.RESET,
                                ),
                        ),
                    ),
                )
            assertEquals(expected, diagnoses.single { it.code == "http_blockpage" }.controlValidated)
            assertNull(diagnoses.single { it.code == "tls_clienthello_rst" }.controlValidated)
        }
    }

    @Test
    fun `unexecuted controls do not count as failed TLS controls`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "control.example",
                        domain = DomainObservationFact(host = "control.example", isControl = true),
                    ),
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "target.example",
                        domain =
                            DomainObservationFact(
                                host = "target.example",
                                transportFailure = TransportFailureKind.RESET,
                            ),
                    ),
                ),
            )
        assertNull(diagnoses.single { it.code == "tls_clienthello_rst" }.controlValidated)
        assertFalse(diagnoses.any { it.code == "network_connectivity_issue" })
    }

    @Test
    fun `TLS and QUIC failures do not identify SNI interference`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "target.example",
                        domain =
                            DomainObservationFact(
                                host = "target.example",
                                transportFailure = TransportFailureKind.RESET,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.QUIC,
                        target = "target.example",
                        quic = QuicObservationFact(host = "target.example", status = QuicProbeStatus.ERROR),
                    ),
                ),
            )
        assertFalse(diagnoses.any { it.code == "sni_triggered_tls_interference" })
        assertEquals(
            "QUIC Initial probe did not receive a valid response",
            diagnoses
                .single {
                    it.code == "quic_blocked"
                }.summary,
        )
    }

    @Test
    fun `failed throughput controls cannot validate a throughput comparison`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.THROUGHPUT,
                        target = "control",
                        throughput =
                            ThroughputObservationFact(
                                label = "control",
                                status = ThroughputProbeStatus.HTTP_UNREACHABLE,
                                isControl = true,
                                medianBps = 10_000_000,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.THROUGHPUT,
                        target = "target",
                        throughput =
                            ThroughputObservationFact(
                                label = "target",
                                status = ThroughputProbeStatus.MEASURED,
                                medianBps = 100_000,
                            ),
                    ),
                ),
            )
        assertFalse(diagnoses.any { it.code == "throttling_suspected" })
    }

    @Test
    fun `ECH success does not imply failure of unexecuted plain TLS`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "target.example",
                        domain = DomainObservationFact(host = "target.example", tlsEchStatus = TlsProbeStatus.OK),
                    ),
                ),
            )
        assertFalse(diagnoses.any { it.code == "tls_ech_only" })
    }

    @Test
    fun `throughput comparison uses throughput controls even when TLS controls fail`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "control.example",
                        domain =
                            DomainObservationFact(
                                host = "control.example",
                                isControl = true,
                                tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.THROUGHPUT,
                        target = "control",
                        throughput =
                            ThroughputObservationFact(
                                label = "control",
                                status = ThroughputProbeStatus.MEASURED,
                                isControl = true,
                                medianBps = 10_000_000,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.THROUGHPUT,
                        target = "target",
                        throughput =
                            ThroughputObservationFact(
                                label = "target",
                                status = ThroughputProbeStatus.MEASURED,
                                medianBps = 100_000,
                            ),
                    ),
                ),
            )
        assertEquals(true, diagnoses.single { it.code == "throttling_suspected" }.controlValidated)
    }
}

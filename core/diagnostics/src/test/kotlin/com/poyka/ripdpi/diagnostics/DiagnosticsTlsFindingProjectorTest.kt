package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsTlsFindingProjectorTest {
    @Test
    fun `transport failure outside TLS does not recreate ClientHello diagnosis`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "example.com",
                        domain =
                            DomainObservationFact(
                                host = "example.com",
                                tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tls12Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tlsError = "connection timed out",
                                transportFailure = TransportFailureKind.OTHER,
                            ),
                    ),
                ),
            )

        assertFalse(diagnoses.any { it.code.startsWith("tls_clienthello_") })
    }

    @Test
    fun `handshake timeout does not claim a ClientHello was sent`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "example.com",
                        domain =
                            DomainObservationFact(
                                host = "example.com",
                                transportFailure = TransportFailureKind.TIMEOUT,
                            ),
                    ),
                ),
            )

        assertEquals("TLS handshake timed out", diagnoses.single { it.code == "tls_clienthello_timeout" }.summary)
    }
}

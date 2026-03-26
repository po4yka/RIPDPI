package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiagnosticsFindingProjectorTest {
    @Test
    fun `classify emits tls ech only diagnosis when clear tls is blocked`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "blocked.example",
                        domain =
                            DomainObservationFact(
                                host = "blocked.example",
                                tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tls12Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tlsEchStatus = TlsProbeStatus.OK,
                                tlsEchVersion = "TLS1.3",
                            ),
                    ),
                ),
            )

        val diagnosis = diagnoses.firstOrNull { it.code == "tls_ech_only" }
        assertNotNull(diagnosis)
        assertEquals("blocked.example", diagnosis?.target)
        assertEquals("Plain TLS is blocked, but ECH succeeds", diagnosis?.summary)
    }
}

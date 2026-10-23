package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PmtuDiagnosisTest {
    @Test
    fun derivesFamilySpecificMinimumPacketFromCompletedAck() {
        assertEquals(1428, observed(PmtuAddressFamily.IPV4).diagnosePmtu().minimumIpPacketBytes)
        assertEquals(1448, observed(PmtuAddressFamily.IPV6).diagnosePmtu().minimumIpPacketBytes)
        assertEquals(PmtuDiagnosisStatus.ConfirmedLowerBound, observed().diagnosePmtu().status)
    }

    @Test
    fun scopeRevocationRemovesCurrentNumericAdvice() {
        val historical = observed().diagnosePmtu(networkScopeUnverified = true)
        assertEquals(PmtuDiagnosisStatus.Historical, historical.status)
        assertNull(historical.minimumIpPacketBytes)
        assertEquals(historical, observed().copy(reason = "network_scope_unverified").diagnosePmtu())
    }

    @Test
    fun partialFailedOrInvalidEvidenceCannotRecommendPacketSize() {
        listOf(PmtuProbeStatus.CANCELLED, PmtuProbeStatus.TIMEOUT, PmtuProbeStatus.FAILED).forEach {
            val partial = observed().copy(status = it, observationWindowComplete = false).diagnosePmtu()
            assertEquals(PmtuDiagnosisStatus.Partial, partial.status)
            assertNull(partial.minimumIpPacketBytes)
        }
        listOf(null, observed().copy(tlsValidated = false), observed().copy(sentProbeCount = 0)).forEach {
            assertEquals(PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable), it.diagnosePmtu())
        }
        assertEquals(
            PmtuDiagnosis(PmtuDiagnosisStatus.Unavailable),
            PmtuProbeEvidence(
                addressFamily = PmtuAddressFamily.IPV4,
                status = PmtuProbeStatus.INCONCLUSIVE,
            ).diagnosePmtu(),
        )
    }

    private fun observed(family: PmtuAddressFamily = PmtuAddressFamily.IPV6) =
        PmtuProbeEvidence(
            addressFamily = family,
            status = PmtuProbeStatus.OBSERVED,
            alpn = "h3",
            tlsValidated = true,
            fragmentationPrevented = true,
            currentUdpPayloadBytes = 1400,
            acknowledgedUdpPayloadLowerBoundBytes = 1400,
            sentProbeCount = 6,
            lostProbeCount = 3,
            observationWindowComplete = true,
            durationMs = 3500,
            handshakeElapsedMs = 100,
        )
}

package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LargePayloadHealthCheckPhasesTest {
    @Test
    fun `HealthCheckPhase has exactly six distinct values`() {
        val phases = HealthCheckPhase.entries
        assertEquals(6, phases.size)
        assert(HealthCheckPhase.TcpConnect in phases)
        assert(HealthCheckPhase.TlsHandshake in phases)
        assert(HealthCheckPhase.SmallResponse in phases)
        assert(HealthCheckPhase.Body64Kb in phases)
        assert(HealthCheckPhase.Body256KbHash in phases)
        assert(HealthCheckPhase.TunnelProtocol in phases)
    }

    @Test
    fun `HealthCheckReport stores independent outcome per phase`() {
        val report =
            HealthCheckReport(
                phases =
                    mapOf(
                        HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.SmallResponse to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.Body64Kb to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.Body256KbHash to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.TunnelProtocol to HealthPhaseOutcome.Failed("timeout"),
                    ),
            )

        assertEquals(HealthPhaseOutcome.Ok, report.phases[HealthCheckPhase.TcpConnect])
        assertEquals(HealthPhaseOutcome.Ok, report.phases[HealthCheckPhase.TlsHandshake])
        assertEquals(HealthPhaseOutcome.Ok, report.phases[HealthCheckPhase.SmallResponse])
        assertEquals(HealthPhaseOutcome.Stalled, report.phases[HealthCheckPhase.Body64Kb])
        assertEquals(HealthPhaseOutcome.Stalled, report.phases[HealthCheckPhase.Body256KbHash])
        assertEquals(HealthPhaseOutcome.Failed("timeout"), report.phases[HealthCheckPhase.TunnelProtocol])
    }

    @Test
    fun `HealthPhaseOutcome Failed carries reason string`() {
        val outcome = HealthPhaseOutcome.Failed("connection_reset")
        assertNotNull(outcome)
        assertEquals("connection_reset", (outcome as HealthPhaseOutcome.Failed).reason)
    }

    @Test
    fun `HealthCheckReport phases are independent — changing one does not affect others`() {
        val phases =
            mutableMapOf<HealthCheckPhase, HealthPhaseOutcome>(
                HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Ok,
            )
        val report = HealthCheckReport(phases = phases)
        // mutate source map after construction — report must be unaffected
        phases[HealthCheckPhase.TcpConnect] = HealthPhaseOutcome.Stalled

        assertEquals(HealthPhaseOutcome.Ok, report.phases[HealthCheckPhase.TcpConnect])
    }
}

package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class DegradedCloudflareClassificationTest {
    private val tlsOkBodyStalledReport =
        HealthCheckReport(
            phases =
                mapOf(
                    HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                    HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Ok,
                    HealthCheckPhase.SmallResponse to HealthPhaseOutcome.Ok,
                    HealthCheckPhase.Body64Kb to HealthPhaseOutcome.Stalled,
                    HealthCheckPhase.Body256KbHash to HealthPhaseOutcome.Stalled,
                    HealthCheckPhase.TunnelProtocol to HealthPhaseOutcome.Ok,
                ),
        )

    @Test
    fun `TLS success and large body stall classifies as DEGRADED_CLOUDFLARE_LIKE`() {
        val state = CloudflareHealthClassifier.classify(tlsOkBodyStalledReport)
        assertEquals(ProfileHealthState.DegradedCloudflareLike, state)
    }

    @Test
    fun `all phases Ok classifies as Healthy`() {
        val report =
            HealthCheckReport(
                phases =
                    mapOf(
                        HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.SmallResponse to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.Body64Kb to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.Body256KbHash to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TunnelProtocol to HealthPhaseOutcome.Ok,
                    ),
            )
        assertEquals(ProfileHealthState.Healthy, CloudflareHealthClassifier.classify(report))
    }

    @Test
    fun `TLS failure does not classify as DEGRADED_CLOUDFLARE_LIKE`() {
        val report =
            HealthCheckReport(
                phases =
                    mapOf(
                        HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Failed("cert_error"),
                        HealthCheckPhase.SmallResponse to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.Body64Kb to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.Body256KbHash to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.TunnelProtocol to HealthPhaseOutcome.Ok,
                    ),
            )
        val state = CloudflareHealthClassifier.classify(report)
        assertEquals(ProfileHealthState.Unknown, state)
    }

    @Test
    fun `TLS ok but only 64KB stall also classifies as DEGRADED_CLOUDFLARE_LIKE`() {
        val report =
            HealthCheckReport(
                phases =
                    mapOf(
                        HealthCheckPhase.TcpConnect to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TlsHandshake to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.SmallResponse to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.Body64Kb to HealthPhaseOutcome.Stalled,
                        HealthCheckPhase.Body256KbHash to HealthPhaseOutcome.Ok,
                        HealthCheckPhase.TunnelProtocol to HealthPhaseOutcome.Ok,
                    ),
            )
        assertEquals(ProfileHealthState.DegradedCloudflareLike, CloudflareHealthClassifier.classify(report))
    }

    @Test
    fun `empty report classifies as Unknown`() {
        val report = HealthCheckReport(phases = emptyMap())
        assertEquals(ProfileHealthState.Unknown, CloudflareHealthClassifier.classify(report))
    }
}

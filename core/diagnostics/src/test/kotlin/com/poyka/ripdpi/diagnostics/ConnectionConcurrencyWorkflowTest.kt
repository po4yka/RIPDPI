package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionConcurrencyWorkflowTest {
    @Test
    fun `confirmed full matrix recommendation carries manual apply profile and cap`() {
        val report =
            scanReportWithStrategyProbe(
                proxyConfigJson = validRecommendedProxyConfigJson(),
                tcpFamily = "hostfake",
                quicFamily = "quic_realistic_burst",
                suiteId = "full_matrix_v1",
                connectionConcurrencyAssessment =
                    ConnectionConcurrencyAssessment(
                        verdict = ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED,
                        selectedProfileId = "firefox_stable",
                        safeCap = 4,
                        plannedCells = 72,
                        cleanCells = 72,
                        affectedTargets = 2,
                        healthyCapsByProfile = mapOf("firefox_stable" to 4),
                    ),
            )

        val enriched =
            DiagnosticsScanWorkflow.enrichScanReport(
                report,
                defaultDiagnosticsAppSettings(),
                preferredDnsPath = null,
            )
        val preferences =
            decodeRipDpiProxyUiPreferences(
                requireNotNull(requireNotNull(enriched.strategyProbeReport).recommendation).recommendedProxyConfigJson,
            )

        assertEquals("firefox_stable", preferences?.fakePackets?.tlsFingerprintProfile)
        assertEquals(
            4,
            preferences
                ?.runtimeContext
                ?.connectionConcurrency
                ?.perProfileCaps
                ?.get("firefox_stable"),
        )
    }
}

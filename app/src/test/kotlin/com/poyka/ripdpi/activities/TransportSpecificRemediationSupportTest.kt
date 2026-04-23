package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransportSpecificRemediationSupportTest {
    @Test
    fun `transport remediation prefers browser fallback when TLS failure points away from QUIC`() {
        assertEquals(
            TransportRemediationKind.BROWSER_FALLBACK,
            recommendTransportRemediation(
                result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                reasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
            ),
        )
    }

    @Test
    fun `transport remediation prefers quic fallback when saved evidence shows udp relay health`() {
        assertEquals(
            TransportRemediationKind.QUIC_FALLBACK,
            recommendTransportRemediation(
                result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                reasonCode = DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE,
                transportClass = null,
                evidence =
                    TransportRemediationEvidence(
                        quicUsable = true,
                        udpUsable = true,
                    ),
            ),
        )
    }

    @Test
    fun `scan remediation ladder opens mode editor for browser relay fallback`() {
        val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

        val ladder =
            support.buildScanRemediationLadder(
                selectedProfile =
                    DiagnosticsProfileOptionUiModel(
                        id = "automatic-probing",
                        name = "Automatic probing",
                        source = "bundled",
                        kind = com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE,
                        strategyProbeSuiteId = StrategyProbeSuiteQuickV1,
                    ),
                workflowRestriction = null,
                resolverRecommendation = null,
                strategyProbeReport =
                    DiagnosticsStrategyProbeReportUiModel(
                        suiteId = StrategyProbeSuiteQuickV1,
                        suiteLabel = "Quick",
                        summaryMetrics = persistentListOf(),
                        completionKind = StrategyProbeCompletionKind.NORMAL,
                        recommendation =
                            DiagnosticsStrategyProbeRecommendationUiModel(
                                headline = "No winner",
                                rationale = "No winner",
                                fields = emptyList(),
                                signature = emptyList(),
                            ),
                        families = persistentListOf(),
                    ),
                latestSession =
                    DiagnosticsSessionRowUiModel(
                        id = "session-1",
                        profileId = "automatic-probing",
                        title = "Latest session",
                        subtitle = "VPN · now",
                        pathMode = "IN_PATH",
                        serviceMode = "VPN",
                        status = "completed",
                        startedAtLabel = "now",
                        summary = "Direct mode failed",
                        metrics = persistentListOf(),
                        tone = DiagnosticsTone.Warning,
                        directModeResult = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                        directModeReasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                        directTransportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                    ),
            )

        assertNotNull(ladder)
        assertEquals(
            DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
            ladder?.primaryAction?.kind,
        )
    }

    @Test
    fun `home remediation ladder opens mode editor for quic relay fallback`() {
        val ladder =
            FakeStringResolver().buildHomeRemediationLadder(
                commandLineBlocked = false,
                fingerprintMismatch = false,
                latestOutcome =
                    HomeDiagnosticsLatestAuditUiState(
                        headline = "No direct solution",
                        summary = "Direct mode unavailable",
                        actionable = true,
                        directModeResult = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                        directModeReasonCode = DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE,
                        transportRemediationEvidence =
                            TransportRemediationEvidence(
                                quicUsable = true,
                                udpUsable = true,
                            ),
                    ),
            )

        assertNotNull(ladder)
        assertEquals(
            DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
            ladder?.primaryAction?.kind,
        )
    }
}

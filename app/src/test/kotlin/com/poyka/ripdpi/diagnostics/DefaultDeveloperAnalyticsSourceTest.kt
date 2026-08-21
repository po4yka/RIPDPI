package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultDeveloperAnalyticsSourceTest {
    @Test
    fun `failed stage envelope uses structured probe evidence and caps categories`() =
        runTest {
            val source =
                DefaultDeveloperAnalyticsSource(
                    appContext = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    breadcrumbBuffer = DeveloperBreadcrumbBuffer(),
                    gitCommit = "test-commit",
                    nativeLibVersion = "test-native",
                )
            val stage =
                DiagnosticsHomeCompositeStageSummary(
                    stageKey = "automatic_audit",
                    stageLabel = "Automatic audit",
                    profileId = "automatic-audit",
                    pathMode = ScanPathMode.RAW_PATH,
                    sessionId = "session-1",
                    status = DiagnosticsHomeCompositeStageStatus.FAILED,
                    headline = "Automatic audit inconclusive",
                    summary = "Raw-path runtime settlement inconclusive",
                )
            val context =
                DeveloperAnalyticsContext(
                    archiveCreatedAtMs = 1L,
                    archiveFileName = "diagnostics.zip",
                    homeCompositeOutcome =
                        DiagnosticsHomeCompositeOutcome(
                            runId = "run-1",
                            actionable = false,
                            headline = "Analysis inconclusive",
                            summary = "Analysis inconclusive",
                            stageSummaries = listOf(stage),
                        ),
                    stageProbeEvidence =
                        listOf(
                            evidence("tcp_connect", "connection_reset"),
                            evidence("strategy_https", "tls_handshake_failed"),
                            evidence("strategy_https", "tls_certificate_failed"),
                            evidence("strategy_https", "tls_alert"),
                            evidence("strategy_https", "tls_timeout"),
                            evidence("strategy_https", "tls_ech_resolution_failed"),
                            evidence("strategy_https", "tls_version_split"),
                            evidence("dns_integrity", "dns_system_resolution_failed"),
                            evidence("strategy_http", "http_status_503"),
                        ),
                )

            val envelope = source.collect(context).failureEnvelopes.single()

            assertEquals(
                listOf(
                    listOf("tcp_connect:connection_reset"),
                    listOf(
                        "strategy_https:tls_handshake_failed",
                        "strategy_https:tls_certificate_failed",
                        "strategy_https:tls_alert",
                        "strategy_https:tls_timeout",
                        "strategy_https:tls_ech_resolution_failed",
                    ),
                    listOf("dns_integrity:dns_system_resolution_failed"),
                    listOf("strategy_http:http_status_503"),
                ),
                listOf(envelope.tcpErrors, envelope.tlsErrors, envelope.dnsErrors, envelope.httpErrors),
            )
        }

    private fun evidence(
        probeType: String,
        outcome: String,
    ) = DeveloperStageProbeEvidence(
        stageKey = "automatic_audit",
        probeType = probeType,
        outcome = outcome,
    )
}

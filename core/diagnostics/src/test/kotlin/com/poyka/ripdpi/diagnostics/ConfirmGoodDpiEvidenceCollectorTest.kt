package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class ConfirmGoodDpiEvidenceCollectorTest {
    @Test
    fun `active controls use the configured relay and two distinct bundled targets`() =
        runTest {
            val relayAddresses = mutableListOf<InetSocketAddress>()
            val targetHosts = mutableListOf<String>()

            val evidence =
                collectActiveConfirmGoodEvidence(
                    intent =
                        strategyIntent(
                            listOf(control("one.example"), control("two.example"), control("one.example")),
                        ),
                    listenerAddress = "127.0.0.1:11980",
                ) { relay, target ->
                    relayAddresses += relay
                    targetHosts += target.host
                    true
                }

            assertEquals(listOf("one.example", "two.example"), targetHosts)
            assertEquals(1, relayAddresses.distinct().size)
            assertEquals(11980, relayAddresses.first().port)
            assertEquals(ConfirmGoodDpiEvidenceSource.ACTIVE, evidence?.source)
            assertEquals(2, evidence?.distinctTargetCount)
            assertEquals(0L, evidence?.applicationResponseBytes)
        }

    @Test
    fun `active evidence requires both controls to stall`() =
        runTest {
            var attempts = 0

            val evidence =
                collectActiveConfirmGoodEvidence(
                    intent = strategyIntent(listOf(control("one.example"), control("two.example"))),
                    listenerAddress = "127.0.0.1:11980",
                ) { _, _ ->
                    attempts += 1
                    attempts == 1
                }

            assertNull(evidence)
        }

    private fun control(host: String) = DomainTarget(host = host, httpsPort = 443, isControl = true)

    private fun strategyIntent(targets: List<DomainTarget>) =
        DiagnosticsIntent(
            profileId = "strategy-probe",
            displayName = "Strategy probe",
            settings = defaultDiagnosticsAppSettings(),
            kind = ScanKind.STRATEGY_PROBE,
            family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
            regionTag = null,
            executionPolicy =
                ExecutionPolicy(
                    manualOnly = true,
                    allowBackground = false,
                    requiresRawPath = true,
                    probePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
                ),
            packRefs = emptyList(),
            domainTargets = targets,
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = StrategyProbeRequest(suiteId = "quick_v1"),
            requestedPathMode = ScanPathMode.RAW_PATH,
        )
}

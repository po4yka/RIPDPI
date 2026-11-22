package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportThroughput
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedEchComparisonPlanningTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val intent =
        strategyProbeIntent(
            settings = settings,
            baseProxyConfigJson = null,
            profileId = EchTransferComparisonProfileId,
            domainTargets = emptyList(),
            quicTargets = emptyList(),
        ).copy(kind = ScanKind.CONNECTIVITY, strategyProbe = null, routeProbe = RouteProbeConfig())
    private val input =
        VerifiedEchComparisonInput(
            url = "https://control.example:8443/body",
            connectIp = "192.0.2.10",
            expectedBodyBytes = "7",
            sha256 = "AB".repeat(32),
        )

    @Test
    fun `comparison requires a DNS origin a fixed IP and a short verified body`() {
        assertTrue(input.isValid())
        assertTrue(input.copy(connectIp = "2001:db8::10", expectedBodyBytes = "32768").isValid())
        listOf(
            input.copy(url = "http://control.example/body"),
            input.copy(url = "https://user@control.example/body"),
            input.copy(url = "https://control.example/body?token=private"),
            input.copy(url = "https://control.example/body#fragment"),
            input.copy(url = "https://control.example:65536/body"),
            input.copy(url = "https://192.0.2.1/body"),
            input.copy(url = "https://[2001:db8::1]/body"),
            input.copy(connectIp = ""),
            input.copy(connectIp = "control.example"),
            input.copy(connectIp = "192.0.2"),
            input.copy(connectIp = "2001:db8::10%eth0"),
            input.copy(connectIp = "0.0.0.0"),
            input.copy(connectIp = "ff02::1"),
            input.copy(expectedBodyBytes = "0"),
            input.copy(expectedBodyBytes = "32769"),
            input.copy(sha256 = "a".repeat(63)),
        ).forEach { assertFalse(it.toString(), it.isValid()) }
    }

    @Test
    fun `one comparison target preserves the fixed address and chosen path through the engine wire`() {
        val json = diagnosticsTestJson()
        val preferredEdges =
            mapOf(
                "control.example" to
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "192.0.2.42",
                            transportKind = PreferredEdgeTransportThroughput,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 5,
                        ),
                    ),
            )
        val context = strategyProbeContext(settings, null, preferredEdges)
        ScanPathMode.entries.forEach { mode ->
            val admitted =
                ActiveProbeSafetyPolicy().enforceTargetBudget(
                    intent.copy(requestedPathMode = mode).withVerifiedEchComparison(input),
                    DiagnosticsScanOrigin.USER_INITIATED,
                    true,
                )
            val plan = DefaultDiagnosticsPlanner().plan(admitted, context.copy(pathMode = mode))
            val wire = DefaultEngineRequestEncoder().encode(plan)
            val decoded =
                json.decodeFromString(
                    EngineScanRequestWire.serializer(),
                    json.encodeToString(EngineScanRequestWire.serializer(), wire),
                )
            val target = decoded.throughputTargets.single()
            assertEquals(mode, decoded.pathMode)
            assertEquals(input.url, target.url)
            assertEquals(DownloadVerification(7, "ab".repeat(32), input.connectIp), target.verification)
            assertEquals(8, target.windowBytes)
            assertEquals(1, target.runs)
            assertNull(target.connectIp)
            assertTrue(target.connectIps.isEmpty())
            assertNull(target.port)
            assertNull(decoded.routeProbe)
            assertEquals(EngineProbeTaskFamily.THROUGHPUT, decoded.probeTasks.single().family)
        }
    }

    @Test
    fun `only manual scans of the comparison profile admit this verification target`() {
        val policy = ActiveProbeSafetyPolicy()
        val prepared = intent.withVerifiedEchComparison(input)
        val target = prepared.throughputTargets.single()
        val ordinaryDownload = target.copy(verification = DownloadVerification(7, input.sha256))
        assertTrue(runCatching { intent.withVerifiedEchComparison(null) }.isFailure)
        assertTrue(runCatching { intent.copy(profileId = "default").withVerifiedEchComparison(input) }.isFailure)
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach { origin ->
            assertTrue(runCatching { policy.enforceTargetBudget(prepared, origin, true) }.isFailure)
        }
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(prepared, DiagnosticsScanOrigin.USER_INITIATED, false)
            }.isFailure,
        )
        listOf(
            prepared.copy(profileId = VerifiedDownloadProfileId),
            prepared.copy(throughputTargets = listOf(target, target)),
            prepared.copy(throughputTargets = listOf(target.copy(connectIp = "192.0.2.1"))),
            prepared.copy(throughputTargets = listOf(target.copy(runs = 3))),
            prepared.copy(throughputTargets = listOf(ordinaryDownload)),
        ).forEach { invalid ->
            assertTrue(
                runCatching {
                    policy.enforceTargetBudget(invalid, DiagnosticsScanOrigin.USER_INITIATED, true)
                }.isFailure,
            )
        }
    }
}

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportThroughput
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedHttp3ComparisonPlanningTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val intent =
        strategyProbeIntent(
            settings = settings,
            baseProxyConfigJson = null,
            profileId = Http3TransferComparisonProfileId,
            domainTargets = emptyList(),
            quicTargets = emptyList(),
        ).copy(kind = ScanKind.CONNECTIVITY, strategyProbe = null, routeProbe = RouteProbeConfig())
    private val input =
        VerifiedTransferComparisonInput("https://fixture.example:8443/body", "192.0.2.10", "32768", "AB".repeat(32))

    @Test
    fun `comparison admits one HTTPS object on a numeric unicast pin`() {
        assertTrue(input.isValidForHttp3())
        assertTrue(input.copy(url = "https://192.0.2.10").isValidForHttp3())
        assertTrue(input.copy(url = "https://[2001:db8::10]:8443/body", connectIp = "2001:db8::10").isValidForHttp3())
        assertTrue(
            input.copy(url = "https://fixture.example/" + "a".repeat(1023), expectedBodyBytes = "1").isValidForHttp3(),
        )
        listOf(
            input.copy(url = "http://fixture.example/body"),
            input.copy(url = "https://user@fixture.example/body"),
            input.copy(url = "https://fixture.example/body?mode=alternate"),
            input.copy(url = "https://fixture.example/body#fragment"),
            input.copy(url = "https://fixture.example//body"),
            input.copy(url = "https://fixture.example./body"),
            input.copy(url = "https://fixture.example/объект"),
            input.copy(url = "https://fixture.example/%x"),
            input.copy(url = "https://fixture.example/" + "a".repeat(1024)),
            input.copy(connectIp = "fixture.example"),
            input.copy(connectIp = "0.0.0.0"),
            input.copy(connectIp = "255.255.255.255"),
            input.copy(connectIp = "ff02::1"),
            input.copy(connectIp = "[2001:db8::10]"),
            input.copy(connectIp = "2001:db8::10%eth0"),
            input.copy(expectedBodyBytes = "0"),
            input.copy(expectedBodyBytes = "32769"),
            input.copy(sha256 = "z".repeat(64)),
        ).forEach { assertFalse(it.toString(), it.isValidForHttp3()) }
    }

    @Test
    fun `wire uses one fixed endpoint even if profile context prefers another address`() {
        val preferredEdges =
            mapOf(
                "fixture.example" to
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "192.0.2.42",
                            transportKind = PreferredEdgeTransportThroughput,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 5,
                        ),
                    ),
            )
        val admitted =
            ActiveProbeSafetyPolicy().enforceTargetBudget(
                intent.withVerifiedHttp3Comparison(input),
                DiagnosticsScanOrigin.USER_INITIATED,
                isManual = true,
            )
        val context = strategyProbeContext(settings, preferredDnsPath = null, preferredEdges)
        val wire = DefaultEngineRequestEncoder().encode(DefaultDiagnosticsPlanner().plan(admitted, context))
        val target = wire.throughputTargets.single()

        assertEquals(ScanPathMode.RAW_PATH, wire.pathMode)
        assertEquals(Http3TransferComparisonProfileId, target.id)
        assertEquals(input.url, target.url)
        assertEquals(input.connectIp, target.connectIp)
        assertTrue(target.connectIps.isEmpty())
        assertNull(target.port)
        assertEquals(1, target.runs)
        assertEquals(32_769, target.windowBytes)
        assertEquals(DownloadVerification(32_768, "ab".repeat(32)), target.verification)
        assertNull(wire.routeProbe)
        assertEquals(EngineProbeTaskFamily.THROUGHPUT, wire.probeTasks.single().family)
    }

    @Test
    fun `only a manual direct scan with a single comparison target is admitted`() {
        val policy = ActiveProbeSafetyPolicy()
        val prepared = intent.withVerifiedHttp3Comparison(input)
        val target = prepared.throughputTargets.single()
        assertTrue(runCatching { intent.withVerifiedHttp3Comparison(null) }.isFailure)
        assertTrue(runCatching { intent.copy(profileId = "default").withVerifiedHttp3Comparison(input) }.isFailure)
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach { origin ->
            assertTrue(runCatching { policy.enforceTargetBudget(prepared, origin, isManual = true) }.isFailure)
        }
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(prepared, DiagnosticsScanOrigin.USER_INITIATED, isManual = false)
            }.isFailure,
        )
        listOf(
            prepared.copy(requestedPathMode = ScanPathMode.IN_PATH),
            prepared.copy(domainTargets = listOf(DomainTarget(host = "other.example"))),
            prepared.copy(throughputTargets = listOf(target, target)),
            prepared.copy(throughputTargets = listOf(target.copy(id = VerifiedDownloadProfileId))),
            prepared.copy(throughputTargets = listOf(target.copy(connectIp = null))),
            prepared.copy(throughputTargets = listOf(target.copy(connectIps = listOf("192.0.2.42")))),
            prepared.copy(throughputTargets = listOf(target.copy(port = 443))),
            prepared.copy(throughputTargets = listOf(target.copy(runs = 2))),
            prepared.copy(throughputTargets = listOf(target.copy(windowBytes = 32_768))),
        ).forEach { invalid ->
            assertTrue(
                runCatching {
                    policy.enforceTargetBudget(invalid, DiagnosticsScanOrigin.USER_INITIATED, isManual = true)
                }.isFailure,
            )
        }
    }
}

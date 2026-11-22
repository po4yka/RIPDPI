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

class VerifiedDownloadPlanningTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val intent =
        strategyProbeIntent(
            settings = settings,
            baseProxyConfigJson = null,
            profileId = VerifiedDownloadProfileId,
            domainTargets = emptyList(),
            quicTargets = emptyList(),
        ).copy(kind = ScanKind.CONNECTIVITY, strategyProbe = null)
    private val input =
        VerifiedDownloadInput(
            url = "https://control.example:8443/file.bin",
            expectedBodyBytes = "7",
            sha256 = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF",
        )

    @Test
    fun `verified download rejects invalid or credential-bearing input`() {
        assertTrue(input.isValid())
        assertTrue(input.copy(expectedBodyBytes = "1048576").isValid())
        listOf(
            input.copy(url = "http://control.example/file.bin"),
            input.copy(url = "https://user@control.example/file.bin"),
            input.copy(url = "https://control.example/file.bin?token=private"),
            input.copy(url = "https://control.example/file.bin#fragment"),
            input.copy(url = "https://control.example:65536/file.bin"),
            input.copy(url = "https://control.example:/file.bin"),
            input.copy(url = "https://control.example/file.bin\n"),
            input.copy(url = "https://control.example/" + "x".repeat(2048)),
            input.copy(expectedBodyBytes = "0"),
            input.copy(expectedBodyBytes = "1048577"),
            input.copy(sha256 = "g".repeat(64)),
            input.copy(sha256 = "a".repeat(63)),
        ).forEach { assertFalse(it.toString(), it.isValid()) }
    }

    @Test
    fun `one verified target round trips to the engine for either manual path`() {
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
                    intent.copy(requestedPathMode = mode).withVerifiedDownload(input),
                    DiagnosticsScanOrigin.USER_INITIATED,
                    true,
                )
            val planned = DefaultDiagnosticsPlanner().plan(admitted, context.copy(pathMode = mode))
            val wire = DefaultEngineRequestEncoder().encode(planned)
            val decoded =
                json.decodeFromString(
                    EngineScanRequestWire.serializer(),
                    json.encodeToString(EngineScanRequestWire.serializer(), wire),
                )
            val target = decoded.throughputTargets.single()
            assertEquals(mode, decoded.pathMode)
            assertEquals(input.url, target.url)
            assertEquals(8, target.windowBytes)
            assertEquals(1, target.runs)
            assertNull(target.connectIp)
            assertTrue(target.connectIps.isEmpty())
            assertNull(decoded.routeProbe)
            assertEquals(
                DownloadVerification(7, "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"),
                target.verification,
            )
            assertEquals(EngineProbeTaskFamily.THROUGHPUT, decoded.probeTasks.single().family)
        }
    }

    @Test
    fun `verified download admission rejects missing input and other origins`() {
        val policy = ActiveProbeSafetyPolicy()
        assertTrue(runCatching { intent.withVerifiedDownload(null) }.isFailure)
        assertTrue(runCatching { intent.copy(profileId = "default").withVerifiedDownload(input) }.isFailure)
        val prepared = intent.withVerifiedDownload(input)
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach { origin ->
            assertTrue(runCatching { policy.enforceTargetBudget(prepared, origin, true) }.isFailure)
        }
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(prepared, DiagnosticsScanOrigin.USER_INITIATED, false)
            }.isFailure,
        )
    }
}

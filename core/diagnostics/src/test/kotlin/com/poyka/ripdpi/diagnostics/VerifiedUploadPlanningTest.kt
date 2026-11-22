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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedUploadPlanningTest {
    private val settings = defaultDiagnosticsAppSettings()
    private val intent =
        strategyProbeIntent(
            settings = settings,
            baseProxyConfigJson = null,
            profileId = VerifiedUploadProfileId,
            domainTargets = emptyList(),
            quicTargets = emptyList(),
        ).copy(kind = ScanKind.CONNECTIVITY, strategyProbe = null, routeProbe = RouteProbeConfig())
    private val upload = VerifiedUploadInput("https://control.example:8443/receipt", "1048576")

    @Test
    fun `upload requires a bounded size and a plain HTTPS endpoint address`() {
        assertTrue(upload.isValid())
        assertTrue(upload.copy(bodyBytes = "1").isValid())
        listOf(
            upload.copy(bodyBytes = "0"),
            upload.copy(bodyBytes = "1048577"),
            upload.copy(url = "http://control.example/receipt"),
            upload.copy(url = "https://user@control.example/receipt"),
            upload.copy(url = "https://control.example/receipt?auth=private"),
        ).forEach { assertFalse(it.isValid()) }
    }

    @Test
    fun `one upload is planned for either manual path without edge pins or route probes`() {
        val json = diagnosticsTestJson()
        val preferredEdge =
            PreferredEdgeCandidate(
                ip = "192.0.2.42",
                transportKind = PreferredEdgeTransportThroughput,
                ipVersion = PreferredEdgeIpVersionV4,
                successCount = 5,
            )
        val context = strategyProbeContext(settings, null, mapOf("control.example" to listOf(preferredEdge)))
        val serializer = EngineScanRequestWire.serializer()
        ScanPathMode.entries.forEach { mode ->
            val admitted =
                ActiveProbeSafetyPolicy().enforceTargetBudget(
                    intent.copy(requestedPathMode = mode).withVerifiedUpload(upload),
                    DiagnosticsScanOrigin.USER_INITIATED,
                    true,
                )
            val plan = DefaultDiagnosticsPlanner().plan(admitted, context.copy(pathMode = mode))
            val wire = DefaultEngineRequestEncoder().encode(plan)
            val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, wire))
            val target = decoded.throughputTargets.single()
            assertEquals(mode, decoded.pathMode)
            assertEquals(upload.url, target.url)
            assertEquals(1_048_576, target.uploadBodyBytes)
            assertEquals(1_048_576, target.windowBytes)
            assertEquals(1, target.runs)
            assertNull(target.verification)
            assertNull(target.connectIp)
            assertTrue(target.connectIps.isEmpty())
            assertNull(decoded.routeProbe)
            assertEquals(EngineProbeTaskFamily.THROUGHPUT, decoded.probeTasks.single().family)
        }
    }

    @Test
    fun `admission rejects background uploads and malformed or mismatched targets`() {
        val policy = ActiveProbeSafetyPolicy()
        val prepared = intent.withVerifiedUpload(upload)
        val target = prepared.throughputTargets.single()
        val origin = DiagnosticsScanOrigin.USER_INITIATED

        assertThrows(IllegalArgumentException::class.java) { intent.withVerifiedUpload(null) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { intent.copy(profileId = "default").withVerifiedUpload(upload) }
        DiagnosticsScanOrigin.entries.filterNot { it == origin }.forEach { otherOrigin ->
            assertThrows(
                IllegalArgumentException::class.java,
            ) { policy.enforceTargetBudget(prepared, otherOrigin, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy.enforceTargetBudget(prepared, origin, false) }
        listOf(
            prepared.copy(profileId = "default"),
            prepared.copy(throughputTargets = emptyList()),
            prepared.copy(throughputTargets = listOf(target, target)),
            prepared.copy(throughputTargets = listOf(target.copy(uploadBodyBytes = 1))),
            prepared.copy(
                throughputTargets = listOf(target.copy(verification = DownloadVerification(1, "a".repeat(64)))),
            ),
        ).forEach { rejected ->
            assertThrows(IllegalArgumentException::class.java) { policy.enforceTargetBudget(rejected, origin, true) }
        }
    }
}

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectiveMatrixPlanningTest {
    @Test
    fun `non-manual origins cannot schedule matrix probes`() {
        val policy = ActiveProbeSafetyPolicy()
        val intent = intent()
        assertEquals(intent, policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, true))
        DiagnosticsScanOrigin.entries.filterNot { it == DiagnosticsScanOrigin.USER_INITIATED }.forEach { origin ->
            assertTrue(origin.name, runCatching { policy.enforceTargetBudget(intent, origin, true) }.isFailure)
        }
        assertTrue(
            runCatching {
                policy.enforceTargetBudget(intent, DiagnosticsScanOrigin.USER_INITIATED, false)
            }.isFailure,
        )
    }

    @Test
    fun `profile projection exposes manual target provenance`() {
        val projection = intent().toProfileProjection()
        assertEquals("test-v1", projection.selectiveMatrix?.catalogVersion)
        assertEquals(
            "2026-10-09",
            projection.selectiveMatrix
                ?.targets
                ?.single()
                ?.sourceDate,
        )
        assertTrue(projection.executionPolicy.manualOnly)
    }

    @Test
    fun `redacted export keeps matrix counts and removes user hosts`() {
        val result =
            EngineProbeResultWire(
                "selective_availability",
                "private-user.example.com",
                "matrix_target_available",
                listOf(
                    ProbeDetail("targetId", "user-1"),
                    ProbeDetail("bodyByteCount", "123"),
                    ProbeDetail("bodyComplete", "true"),
                    ProbeDetail("sourceDate", "2026-10-09"),
                    ProbeDetail("sourceUrl", "https://private-user.example.com/"),
                ),
            )
        val report =
            EngineScanReportWire(
                sessionId = "test",
                profileId = SelectiveMatrixProfileId,
                pathMode = ScanPathMode.RAW_PATH,
                startedAt = 0,
                finishedAt = 1,
                summary = "Matrix",
                results = listOf(result),
            )
        val redacted = requireNotNull(DiagnosticsArchiveRedactor(RipDpiJson).redact(report))
        val encoded = RipDpiJson.encodeToString(EngineScanReportWire.serializer(), redacted)
        assertFalse(encoded.contains("private-user"))
        assertTrue(
            redacted.results
                .single()
                .details
                .contains(ProbeDetail("bodyByteCount", "123")),
        )
        assertTrue(
            redacted.results
                .single()
                .details
                .contains(ProbeDetail("sourceDate", "2026-10-09")),
        )
    }

    private fun intent() =
        DiagnosticsIntent(
            profileId = SelectiveMatrixProfileId,
            displayName = "Matrix",
            settings = defaultDiagnosticsAppSettings(),
            kind = ScanKind.CONNECTIVITY,
            family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
            regionTag = "ru",
            executionPolicy = ExecutionPolicy(true, false, false, ProbePersistencePolicy.MANUAL_ONLY),
            packRefs = emptyList(),
            domainTargets = emptyList(),
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = null,
            requestedPathMode = ScanPathMode.RAW_PATH,
            selectiveMatrix =
                SelectiveMatrixConfig(
                    catalogVersion = "test-v1",
                    targets =
                        listOf(
                            SelectiveMatrixTarget(
                                "control",
                                "Control",
                                "https://cloudflare.com/robots.txt",
                                "global",
                                "cloudflare",
                                "https://cloudflare.com",
                                "2026-10-09",
                            ),
                        ),
                ),
        )
}

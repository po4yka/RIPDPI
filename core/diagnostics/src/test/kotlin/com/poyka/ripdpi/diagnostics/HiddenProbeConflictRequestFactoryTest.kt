package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenProbeConflictRequestFactoryTest {
    private val json = diagnosticsTestJson()
    private val factory =
        HiddenProbeConflictRequestFactory(
            json = json,
            requestIdGenerator = { "request-1" },
        )

    @Test
    fun `create maps full matrix automatic audit profile to conflict result`() {
        val request =
            factory.create(
                profile =
                    profile(
                        id = "automatic-audit",
                        name = "Automatic audit",
                        kind = ScanKind.STRATEGY_PROBE,
                        suiteId = "full_matrix_v1",
                    ),
                settings = defaultDiagnosticsAppSettings(),
                pathMode = ScanPathMode.RAW_PATH,
            )

        val result = request.toConflictResult()

        assertEquals("request-1", result.requestId)
        assertEquals("Automatic audit", result.profileName)
        assertEquals(ScanPathMode.RAW_PATH, result.pathMode)
        assertEquals(ScanKind.STRATEGY_PROBE, result.scanKind)
        assertTrue(result.isFullAudit)
    }

    @Test
    fun `create does not mark quick strategy profile as full audit`() {
        val request =
            factory.create(
                profile =
                    profile(
                        id = "automatic-probing",
                        name = "Automatic probing",
                        kind = ScanKind.STRATEGY_PROBE,
                        suiteId = "quick_v1",
                    ),
                settings = defaultDiagnosticsAppSettings(),
                pathMode = ScanPathMode.RAW_PATH,
            )

        assertFalse(request.toConflictResult().isFullAudit)
    }

    private fun profile(
        id: String,
        name: String,
        kind: ScanKind,
        suiteId: String,
    ): DiagnosticProfileEntity =
        DiagnosticProfileEntity(
            id = id,
            name = name,
            source = "bundled",
            version = 1,
            requestJson =
                diagnosticsProfileRequestJson(
                    json = json,
                    profileId = id,
                    displayName = name,
                    kind = kind,
                    targets =
                        DiagnosticsProfileTargets(
                            strategyProbe = StrategyProbeRequest(suiteId = suiteId),
                        ),
                ),
            updatedAt = 1L,
        )
}

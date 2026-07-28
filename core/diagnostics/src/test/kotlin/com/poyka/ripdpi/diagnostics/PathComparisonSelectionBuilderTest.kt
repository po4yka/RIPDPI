@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathComparisonSelectionBuilderTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `successful control plus failed target produces path comparison selection`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val control = DomainTarget(host = "control.example", isControl = true)
            val failed = DomainTarget(host = "failed.example")
            stores.profilesState.value = listOf(profile(control, failed))
            val report = report(control, failed)
            stores.sessionsState.value = listOf(session(report))

            val selection =
                buildPathComparisonSelection(
                    runId = "run",
                    progressState = progress(report),
                    diagnosticsProfileCatalog = stores,
                    scanRecordStore = stores,
                    json = json,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                )

            assertNotNull(selection)
            val requiredSelection = requireNotNull(selection)
            assertEquals(listOf("failed.example"), requiredSelection.failedTargetLabels)
            assertEquals(
                setOf("control.example", "failed.example"),
                requiredSelection.domainTargets.mapTo(mutableSetOf()) { it.host },
            )
            assertTrue(requiredSelection.domainTargets.single { it.host == "control.example" }.isControl)
        }

    private fun profile(
        control: DomainTarget,
        failed: DomainTarget,
    ) = DiagnosticProfileEntity(
        id = "ru-dpi-full",
        name = "Russia DPI Full",
        source = "bundled",
        version = 1,
        requestJson =
            diagnosticsProfileRequestJson(
                json = json,
                profileId = "ru-dpi-full",
                displayName = "Russia DPI Full",
                targets = DiagnosticsProfileTargets(domainTargets = listOf(control, failed)),
            ),
        updatedAt = 1L,
    )

    private fun report(
        control: DomainTarget,
        failed: DomainTarget,
    ) = ScanReport(
        sessionId = "dpi-session",
        profileId = "ru-dpi-full",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 1L,
        finishedAt = 2L,
        summary = "Control succeeds while target fails",
        observations = listOf(controlObservation(control), failedObservation(failed)),
    )

    private fun controlObservation(control: DomainTarget) =
        ObservationFact(
            kind = ObservationKind.DOMAIN,
            target = control.host,
            domain =
                DomainObservationFact(
                    host = control.host,
                    tls13Status = TlsProbeStatus.OK,
                    isControl = true,
                ),
        )

    private fun failedObservation(failed: DomainTarget) =
        ObservationFact(
            kind = ObservationKind.DOMAIN,
            target = failed.host,
            domain =
                DomainObservationFact(
                    host = failed.host,
                    transportFailure = TransportFailureKind.RESET,
                ),
        )

    private fun session(report: ScanReport) =
        diagnosticsSession(
            id = report.sessionId,
            profileId = report.profileId,
            pathMode = report.pathMode.name,
            summary = report.summary,
            reportJson =
                json.encodeToString(
                    EngineScanReportWire.serializer(),
                    report.toEngineScanReportWire(),
                ),
        )

    private fun progress(report: ScanReport) =
        MutableStateFlow(
            mapOf(
                "run" to
                    DiagnosticsHomeCompositeProgress(
                        runId = "run",
                        stages =
                            listOf(
                                DiagnosticsHomeCompositeStageSummary(
                                    stageKey = "dpi_full",
                                    stageLabel = "DPI full",
                                    profileId = "ru-dpi-full",
                                    pathMode = ScanPathMode.RAW_PATH,
                                    status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                    headline = "Complete",
                                    summary = report.summary,
                                    sessionId = report.sessionId,
                                ),
                            ),
                    ),
            ),
        )
}

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
import org.junit.Assert.assertNull
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
            val selection = select(stores, listOf(control, failed), report(control, failed))

            assertNotNull(selection)
            val requiredSelection = requireNotNull(selection)
            assertEquals(listOf("failed.example"), requiredSelection.failedTargetLabels)
            assertEquals(
                setOf("control.example", "failed.example"),
                requiredSelection.domainTargets.mapTo(mutableSetOf()) { it.host },
            )
            assertTrue(requiredSelection.domainTargets.single { it.host == "control.example" }.isControl)
        }

    @Test
    fun `a domestic control is never a comparison control`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val domestic = DomainTarget(host = "ya.ru", isControl = true, controlTier = ControlTier.DOMESTIC)
            val failed = DomainTarget(host = "failed.example")
            val selection = select(stores, listOf(domestic, failed), report(domestic, failed))

            // The domestic site answers on the direct path anyway, so it cannot show what the bypass changes.
            assertTrue(selection?.domainTargets.orEmpty().none { it.host == "ya.ru" })
        }

    @Test
    fun `a TLS-only control cannot establish an HTTP comparison baseline`() =
        runTest {
            val control = DomainTarget(host = "control.example", isControl = true)
            val failed = DomainTarget(host = "failed.example")
            val raw =
                report(control, failed).copy(
                    observations =
                        listOf(
                            successfulHttpObservation(control, HttpProbeStatus.NOT_RUN),
                            failedObservation(failed),
                        ),
                )

            assertNull(select(FakeDiagnosticsHistoryStores(), listOf(control, failed), raw))
        }

    @Test
    fun `partial RAW reports cannot seed a path comparison`() =
        runTest {
            val control = DomainTarget(host = "control.example", isControl = true)
            val failed = DomainTarget(host = "failed.example")
            val raw = report(control, failed).copy(completionKind = ScanCompletionKind.PARTIAL_RESULTS)

            assertNull(select(FakeDiagnosticsHistoryStores(), listOf(control, failed), raw))
        }

    @Test
    fun `the working domain keeps its HTTP parameters and takes one existing target slot`() =
        runTest {
            val control = DomainTarget(host = "control.example", isControl = true)
            val working = DomainTarget(host = "working.example", httpPort = 8080, httpPath = "/probe")
            val failed = List(3) { index -> DomainTarget(host = "failed-$index.example") }
            val raw =
                report(control, failed.first()).copy(
                    observations =
                        listOf(
                            successfulHttpObservation(control, HttpProbeStatus.NOT_RUN),
                            successfulHttpObservation(working),
                        ) +
                            failed.map(::failedObservation),
                )
            val selection =
                requireNotNull(select(FakeDiagnosticsHistoryStores(), listOf(control, working) + failed, raw))

            assertEquals(listOf(working) + failed.take(2), selection.domainTargets)
            assertEquals(listOf("failed-0.example", "failed-1.example"), selection.failedTargetLabels)
        }

    private suspend fun select(
        stores: FakeDiagnosticsHistoryStores,
        targets: List<DomainTarget>,
        report: ScanReport,
    ): PathComparisonSelection? {
        stores.profilesState.value = listOf(profile(targets))
        stores.sessionsState.value = listOf(session(report))
        return buildPathComparisonSelection(
            runId = "run",
            progressState = progress(report),
            diagnosticsProfileCatalog = stores,
            scanRecordStore = stores,
            json = json,
            serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
        )
    }

    private fun profile(targets: List<DomainTarget>) =
        DiagnosticProfileEntity(
            id = "ru-dpi-full",
            name = "Russia DPI Full",
            source = "bundled",
            version = 1,
            requestJson =
                diagnosticsProfileRequestJson(
                    json = json,
                    profileId = "ru-dpi-full",
                    displayName = "Russia DPI Full",
                    targets = DiagnosticsProfileTargets(domainTargets = targets),
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
        observations = listOf(successfulHttpObservation(control), failedObservation(failed)),
    )

    private fun successfulHttpObservation(
        target: DomainTarget,
        httpStatus: HttpProbeStatus = HttpProbeStatus.OK,
    ) = ObservationFact(
        kind = ObservationKind.DOMAIN,
        target = target.host,
        domain =
            DomainObservationFact(
                host = target.host,
                httpStatus = httpStatus,
                tls13Status = TlsProbeStatus.OK,
                isControl = target.isControl,
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

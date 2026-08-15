package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageExecutorResumePolicyTest {
    @Test
    fun `raw stage requests runtime resume while in path stage does not`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val timelineSource =
                DefaultDiagnosticsTimelineSource(
                    profileCatalog = stores,
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    bypassUsageHistoryStore = stores,
                    mapper = DiagnosticsBoundaryMapper(json),
                    scope = backgroundScope,
                    json = json,
                )
            val starts = mutableListOf<Pair<ScanPathMode, Boolean>>()
            val controller =
                object : DiagnosticsScanController {
                    override val hiddenAutomaticProbeActive = MutableStateFlow(false)

                    override suspend fun startScan(
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                    ): DiagnosticsManualScanStartResult = error("owned start expected")

                    override suspend fun startScanOwnedBy(
                        ownerId: String,
                        pathMode: ScanPathMode,
                        selectedProfileId: String?,
                        skipActiveScanCheck: Boolean,
                        allowSensitiveProfileStart: Boolean,
                        scanDeadlineMs: Long?,
                        maxCandidates: Int?,
                        targetOverrides: DiagnosticsScanTargetOverrides?,
                        resumeRuntimeAfterRawPath: Boolean,
                    ): DiagnosticsManualScanStartResult {
                        starts += pathMode to resumeRuntimeAfterRawPath
                        return DiagnosticsManualScanStartResult.Started("session-${starts.size}")
                    }

                    override suspend fun resolveHiddenProbeConflict(
                        requestId: String,
                        action: HiddenProbeConflictAction,
                    ): DiagnosticsManualScanResolution = error("unused")

                    override suspend fun cancelActiveScan() = Unit

                    override suspend fun setActiveProfile(profileId: String) = Unit
                }
            val specs =
                listOf(
                    HomeCompositeStageSpec("raw", "Raw", "raw-profile", ScanPathMode.RAW_PATH),
                    HomeCompositeStageSpec("in-path", "In path", "in-path-profile", ScanPathMode.IN_PATH),
                )
            val progress =
                MutableStateFlow(
                    mapOf(
                        "run" to
                            DiagnosticsHomeCompositeProgress(
                                runId = "run",
                                stages =
                                    specs.map { spec ->
                                        DiagnosticsHomeCompositeStageSummary(
                                            stageKey = spec.key,
                                            stageLabel = spec.label,
                                            profileId = spec.profileId,
                                            pathMode = spec.pathMode,
                                            status = DiagnosticsHomeCompositeStageStatus.PENDING,
                                            headline = "Pending",
                                            summary = "Pending",
                                        )
                                    },
                            ),
                    ),
                )
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                )

            specs.forEachIndexed { index, spec ->
                executor.launchStageSession(
                    runId = "run",
                    stageIndex = index,
                    spec = spec,
                    quickScan = false,
                    progressState = progress,
                )
            }

            assertEquals(
                listOf(ScanPathMode.RAW_PATH to true, ScanPathMode.IN_PATH to false),
                starts,
            )
        }
}

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeStageExecutorResumePolicyTest {
    @Test
    fun `strategy stages reserve finalization time after the native deadline`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val spec = HomeCompositeStageSpecs.first { it.profileId == "ru-dpi-strategy" }
            val progress = MutableStateFlow(mapOf("run" to pendingProgress(spec)))
            val nativeDeadlines = mutableListOf<Long?>()
            val controller =
                object : RuntimeUnavailableController() {
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
                        nativeDeadlines += scanDeadlineMs
                        return DiagnosticsManualScanStartResult.Started("session-${nativeDeadlines.size}")
                    }
                }
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = timelineSource(stores, json, backgroundScope),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                )

            listOf(false, true).forEach { quickScan ->
                executor.launchStageSession("run", 0, spec, quickScan, progress)
            }

            assertEquals(
                listOf(330_000L to 270_000L, 120_000L to 60_000L),
                listOf(
                    stageTimeoutMs(spec) to nativeDeadlines[0],
                    stageTimeoutMs(spec, quickScan = true) to nativeDeadlines[1],
                ),
            )
        }

    @Test
    fun `active vpn allows controller to acquire the owned in-path route`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val spec = HomeCompositeStageSpec("in-path", "In path", "in-path-profile", ScanPathMode.IN_PATH)
            val progress = MutableStateFlow(mapOf("run" to pendingProgress(spec)))
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = RuntimeUnavailableController(),
                    diagnosticsTimelineSource =
                        DefaultDiagnosticsTimelineSource(
                            profileCatalog = stores,
                            scanRecordStore = stores,
                            artifactReadStore = stores,
                            bypassUsageHistoryStore = stores,
                            mapper = DiagnosticsBoundaryMapper(json),
                            scope = backgroundScope,
                            json = json,
                        ),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    runtimeCoordinator =
                        FakeDiagnosticsRuntimeCoordinator(
                            DiagnosticsInPathRouteLease(
                                runtimeId = "vpn-runtime",
                                routeGeneration = 1,
                                issuedRevision = 1L,
                                host = "127.0.0.1",
                                port = 19080,
                                credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
                            ),
                        ),
                )

            assertEquals(true, executor.requireInPathRuntime(progress, "run", 0, spec))
            assertEquals(
                null,
                progress.value
                    .getValue("run")
                    .stages
                    .single()
                    .unavailableReason,
            )
        }

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

    @Test
    fun `runtime loss at authoritative in-path start is unavailable instead of skipped`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val spec = HomeCompositeStageSpec("in-path", "In path", "in-path-profile", ScanPathMode.IN_PATH)
            val progress = MutableStateFlow(mapOf("run" to pendingProgress(spec)))
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = RuntimeUnavailableController(),
                    diagnosticsTimelineSource =
                        DefaultDiagnosticsTimelineSource(
                            profileCatalog = stores,
                            scanRecordStore = stores,
                            artifactReadStore = stores,
                            bypassUsageHistoryStore = stores,
                            mapper = DiagnosticsBoundaryMapper(json),
                            scope = backgroundScope,
                            json = json,
                        ),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                )

            val sessionId =
                executor.launchStageSession(
                    runId = "run",
                    stageIndex = 0,
                    spec = spec,
                    quickScan = false,
                    progressState = progress,
                )

            assertEquals(
                null to DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                sessionId to
                    progress.value
                        .getValue("run")
                        .stages
                        .single()
                        .status,
            )
        }

    @Test
    fun `cancellation while starting a stage is propagated`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val spec = HomeCompositeStageSpec("raw", "Raw", "raw-profile", ScanPathMode.RAW_PATH)
            val progress = MutableStateFlow(mapOf("run" to pendingProgress(spec)))
            val controller =
                object : RuntimeUnavailableController() {
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
                    ): DiagnosticsManualScanStartResult = throw CancellationException("parent cancelled")
                }
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = timelineSource(stores, json, backgroundScope),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                )

            val failure =
                runCatching {
                    executor.launchStageSession("run", 0, spec, false, progress)
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }

    @Test
    fun `vpn halt retires the owned in-path stage session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val json = diagnosticsTestJson()
            val spec = HomeCompositeStageSpec("in-path", "In path", "in-path-profile", ScanPathMode.IN_PATH)
            val progress = MutableStateFlow(mapOf("run" to pendingProgress(spec)))
            val stateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            var cancelledSessionId: String? = null
            val controller =
                object : RuntimeUnavailableController() {
                    override suspend fun cancelScan(sessionId: String) {
                        cancelledSessionId = sessionId
                    }
                }
            val executor =
                HomeCompositeStageExecutor(
                    diagnosticsScanController = controller,
                    diagnosticsTimelineSource = timelineSource(stores, json, backgroundScope),
                    serviceStateStore = stateStore,
                )
            val awaiting =
                async {
                    executor.awaitStageSignal("run", 0, spec, "session-in-path", progress)
                }
            runCurrent()

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            awaiting.await()

            assertEquals("session-in-path", cancelledSessionId)
            assertEquals(
                DiagnosticsHomeCompositeStageStatus.FAILED,
                progress.value
                    .getValue("run")
                    .stages
                    .single()
                    .status,
            )
        }
}

private fun timelineSource(
    stores: FakeDiagnosticsHistoryStores,
    json: kotlinx.serialization.json.Json,
    scope: kotlinx.coroutines.CoroutineScope,
): DiagnosticsTimelineSource =
    DefaultDiagnosticsTimelineSource(
        profileCatalog = stores,
        scanRecordStore = stores,
        artifactReadStore = stores,
        bypassUsageHistoryStore = stores,
        mapper = DiagnosticsBoundaryMapper(json),
        scope = scope,
        json = json,
    )

private fun pendingProgress(spec: HomeCompositeStageSpec) =
    DiagnosticsHomeCompositeProgress(
        runId = "run",
        stages =
            listOf(
                DiagnosticsHomeCompositeStageSummary(
                    stageKey = spec.key,
                    stageLabel = spec.label,
                    profileId = spec.profileId,
                    pathMode = spec.pathMode,
                    status = DiagnosticsHomeCompositeStageStatus.PENDING,
                    headline = "Pending",
                    summary = "Pending",
                ),
            ),
    )

private open class RuntimeUnavailableController : DiagnosticsScanController {
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
    ): DiagnosticsManualScanStartResult =
        throw InPathRuntimeUnavailableException("runtime changed before in-path start")

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution = error("unused")

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit
}

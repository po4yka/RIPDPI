package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerHiddenProbeTest {
    private val json = diagnosticsTestJson()
    private val automaticProbeFingerprintProvider = FakeNetworkFingerprintProvider()

    @Test
    fun `manual start during hidden automatic probe returns conflict result`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = appSettingsRepository,
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertEquals(
                AutomaticProbeLaunchOutcome.LAUNCHED,
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            deliveryId = "delivery-hidden-conflict",
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = automaticProbeFingerprintProvider.capture().scopeKey(),
                            classification = "transport_switch",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            occurredAt = 10L,
                        ),
                ),
            )

            val result = services.scanController.startScan(ScanPathMode.RAW_PATH)

            assertTrue(result is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution)
            val conflict = result as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution
            assertEquals("Automatic audit", conflict.profileName)
            assertEquals(ScanPathMode.RAW_PATH, conflict.pathMode)
            assertEquals(ScanKind.STRATEGY_PROBE, conflict.scanKind)
            assertTrue(conflict.isFullAudit)
            assertTrue(services.scanController.hiddenAutomaticProbeActive.value)
        }

    @Test
    fun `wait resolution starts queued manual request from original snapshot`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services = createServicesWithHiddenProbeCapable(appSettingsRepository, stores, bridgeFactory)

            assertEquals(
                AutomaticProbeLaunchOutcome.LAUNCHED,
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event = automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
                ),
            )
            val conflict =
                services.scanController.startScan(ScanPathMode.RAW_PATH)
                    as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution

            appSettingsRepository.update { diagnosticsActiveProfileId = "default" }

            val hiddenSessionId =
                stores.sessionsState.value
                    .single()
                    .id
            completeHiddenScan(bridgeFactory, hiddenSessionId, settings)
            advanceUntilIdle()

            val resolution =
                services.scanController.resolveHiddenProbeConflict(
                    requestId = conflict.requestId,
                    action = HiddenProbeConflictAction.WAIT,
                )

            val sessionId = resolution.startedSessionId()
            assertEquals("automatic-audit", stores.getScanSession(sessionId)?.profileId)
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)
        }

    private fun kotlinx.coroutines.test.TestScope.createServicesWithHiddenProbeCapable(
        appSettingsRepository: FakeAppSettingsRepository,
        stores: FakeDiagnosticsHistoryStores,
        bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    ) = createDiagnosticsServices(
        context = TestContext(),
        appSettingsRepository = appSettingsRepository,
        stores = stores,
        networkMetadataProvider = FakeNetworkMetadataProvider(),
        diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
        networkDiagnosticsBridgeFactory = bridgeFactory,
        runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
        serviceStateStore = FakeServiceStateStore(),
        networkFingerprintProvider = automaticProbeFingerprintProvider,
        scope = backgroundScope,
        controllerScope = this,
        json = json,
    )

    @Test
    fun `cancel and run cancels hidden probe with dedicated summary`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertEquals(
                AutomaticProbeLaunchOutcome.LAUNCHED,
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            deliveryId = "delivery-stop-and-start",
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = automaticProbeFingerprintProvider.capture().scopeKey(),
                            classification = "transport_switch",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            occurredAt = 10L,
                        ),
                ),
            )
            val conflict =
                services.scanController.startScan(ScanPathMode.RAW_PATH)
                    as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution

            val resolution =
                services.scanController.resolveHiddenProbeConflict(
                    requestId = conflict.requestId,
                    action = HiddenProbeConflictAction.CANCEL_AND_RUN,
                )
            val manualSessionId = resolution.startedSessionId()
            advanceUntilIdle()

            val hiddenSession =
                stores.sessionsState.value.first { it.id != manualSessionId }
            assertEquals("failed", hiddenSession.status)
            assertEquals(
                BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                hiddenSession.summary,
            )
            assertEquals(1, bridgeFactory.bridge.cancelCount)
        }

    @Test
    fun `natural completion while cancellation cause persists is not overwritten`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertEquals(
                AutomaticProbeLaunchOutcome.LAUNCHED,
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event = automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
                ),
            )
            val conflict =
                services.scanController.startScan(ScanPathMode.RAW_PATH)
                    as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution
            val hiddenSessionId =
                stores.sessionsState.value
                    .single()
                    .id
            val causePersisted = CompletableDeferred<Unit>()
            val releaseCausePersistence = CompletableDeferred<Unit>()
            stores.afterUpsertScanSession = { session ->
                if (
                    session.id == hiddenSessionId &&
                    session.status == "running" &&
                    session.summary == BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
                ) {
                    causePersisted.complete(Unit)
                    releaseCausePersistence.await()
                }
            }

            val resolution =
                async {
                    services.scanController.resolveHiddenProbeConflict(
                        requestId = conflict.requestId,
                        action = HiddenProbeConflictAction.CANCEL_AND_RUN,
                    )
                }
            causePersisted.await()
            completeHiddenScan(bridgeFactory, hiddenSessionId, settings)
            advanceUntilIdle()

            val naturallyCompleted = requireNotNull(stores.getScanSession(hiddenSessionId))
            assertEquals("completed", naturallyCompleted.status)
            assertEquals("strategy probe", naturallyCompleted.summary)
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)

            releaseCausePersistence.complete(Unit)
            resolution.await().startedSessionId()
            advanceUntilIdle()

            val preserved = requireNotNull(stores.getScanSession(hiddenSessionId))
            assertEquals("completed", preserved.status)
            assertEquals("strategy probe", preserved.summary)
            assertEquals(0, bridgeFactory.bridge.cancelCount)
        }

    @Test
    fun `manual conflict remains authoritative when partial report is persisted first`() =
        runTest {
            assertManualConflictCancellationSurvives(ManualConflictPersistenceOrder.PARTIAL_FIRST)
        }

    @Test
    fun `manual conflict remains authoritative when sentinel is persisted first`() =
        runTest {
            assertManualConflictCancellationSurvives(ManualConflictPersistenceOrder.SENTINEL_FIRST)
        }

    private suspend fun TestScope.assertManualConflictCancellationSurvives(order: ManualConflictPersistenceOrder) {
        val settings =
            defaultDiagnosticsAppSettings()
                .toBuilder()
                .setDiagnosticsActiveProfileId("automatic-audit")
                .setNetworkStrategyMemoryEnabled(true)
                .build()
        val stores =
            FakeDiagnosticsHistoryStores().apply {
                seedStrategyProbeProfile(json)
                addAutomaticAuditProfile(json)
            }
        val serviceStateStore = FakeServiceStateStore()
        val bridgeFactory =
            FakeNetworkDiagnosticsBridgeFactory(json).apply {
                bridge.autoCompleteOnStart = false
            }
        val services =
            createDiagnosticsServices(
                context = TestContext(),
                appSettingsRepository = FakeAppSettingsRepository(settings),
                stores = stores,
                networkMetadataProvider = FakeNetworkMetadataProvider(),
                diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                networkDiagnosticsBridgeFactory = bridgeFactory,
                runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                serviceStateStore = serviceStateStore,
                networkFingerprintProvider = automaticProbeFingerprintProvider,
                scope = backgroundScope,
                controllerScope = this,
                json = json,
            )

        assertEquals(
            AutomaticProbeLaunchOutcome.LAUNCHED,
            services.scanController.launchAutomaticProbe(
                settings = settings,
                event = automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
            ),
        )
        val conflict =
            services.scanController.startScan(ScanPathMode.RAW_PATH)
                as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution
        val hiddenSessionId =
            stores.sessionsState.value
                .single()
                .id
        val partialReport =
            controllerStrategyProbeReport(hiddenSessionId, settings).copy(
                profileId = "automatic-audit",
                summary = "Native partial completion",
                completionKind = ScanCompletionKind.PARTIAL_RESULTS,
                terminationReason = ScanTerminationReason.USER_CANCELLED,
            )

        if (order == ManualConflictPersistenceOrder.PARTIAL_FIRST) {
            persistControllerPartialReport(partialReport, stores, serviceStateStore)
        }

        services.scanController
            .resolveHiddenProbeConflict(conflict.requestId, HiddenProbeConflictAction.CANCEL_AND_RUN)
            .startedSessionId()
        advanceUntilIdle()

        if (order == ManualConflictPersistenceOrder.SENTINEL_FIRST) {
            persistControllerPartialReport(partialReport, stores, serviceStateStore)
        }

        assertManualConflictPreserved(stores, hiddenSessionId)
    }

    private suspend fun assertManualConflictPreserved(
        stores: FakeDiagnosticsHistoryStores,
        hiddenSessionId: String,
    ) {
        val entity = requireNotNull(stores.getScanSession(hiddenSessionId))
        val boundarySession = DiagnosticsBoundaryMapper(json).toDiagnosticScanSession(entity)
        val archive =
            DiagnosticsSummaryProjector().project(
                session = entity,
                report = boundarySession.report,
                latestSnapshotModel = null,
                latestContextModel = null,
                latestTelemetry = null,
                selectedResults = emptyList(),
                warnings = emptyList(),
            )

        assertEquals("failed", entity.status)
        assertEquals(BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary, entity.summary)
        assertEquals(BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary, boundarySession.summary)
        assertEquals(ScanCompletionKind.PARTIAL_RESULTS, boundarySession.report?.completionKind)
        assertTrue(
            archive.header.lines.contains(
                "summary=$BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary",
            ),
        )
    }

    private suspend fun persistControllerPartialReport(
        report: ScanReport,
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: FakeServiceStateStore,
    ) {
        DiagnosticsReportPersister.persistScanReport(
            report = report.toEngineScanReportWire(),
            scanRecordStore = stores,
            artifactWriteStore = stores,
            serviceStateStore = serviceStateStore,
            json = json,
        )
    }
}

private enum class ManualConflictPersistenceOrder {
    PARTIAL_FIRST,
    SENTINEL_FIRST,
}

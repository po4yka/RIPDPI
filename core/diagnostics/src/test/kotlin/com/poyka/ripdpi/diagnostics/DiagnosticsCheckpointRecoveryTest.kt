package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsCheckpointRecoveryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `polling ignores checkpoint report and finalizes terminal report`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    enqueueReport(
                        checkpointReport("checkpoint-session", "Checkpoint"),
                        ScanReportDisposition.CHECKPOINT,
                    )
                    enqueueProgress(finishedProgress("checkpoint-session"))
                    enqueueReport(
                        checkpointReport("checkpoint-session", "Terminal"),
                        ScanReportDisposition.TERMINAL,
                    )
                }
            val registry = ActiveScanRegistry(coordinatorTimelineSource(stores, backgroundScope))
            registry.registerBridge(bridge, "checkpoint-session", registerActiveBridge = true)
            val finalizedReports = mutableListOf<String>()

            BridgePollingService(PassiveEventPersistenceService(stores, json), json).awaitCompletion(
                prepared = preparedDiagnosticsScan("checkpoint-session", defaultDiagnosticsAppSettings()),
                handle = BridgeSessionHandle(bridge, "checkpoint-session", registerActiveBridge = true),
                activeScanRegistry = registry,
            ) { reportJson -> finalizedReports += reportJson }

            assertEquals(
                listOf("Terminal"),
                finalizedReports.map { reportJson -> json.decodeEngineScanReportWire(reportJson).summary },
            )
        }

    @Test
    fun `polling rejects malformed disposition and waits for terminal report`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val malformedDisposition =
                json
                    .encodeToString(
                        EngineScanReportWire.serializer(),
                        checkpointReport("malformed-disposition", "Malformed")
                            .toEngineScanReportWire()
                            .copy(reportDisposition = ScanReportDisposition.CHECKPOINT),
                    ).replace("\"CHECKPOINT\"", "\"UNKNOWN_DISPOSITION\"")
            val terminal =
                json.encodeToString(
                    EngineScanReportWire.serializer(),
                    checkpointReport("malformed-disposition", "Terminal").toEngineScanReportWire(),
                )
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    enqueueReport(malformedDisposition)
                    enqueueReport(terminal)
                }
            val registry = ActiveScanRegistry(coordinatorTimelineSource(stores, backgroundScope))
            registry.registerBridge(bridge, "malformed-disposition", registerActiveBridge = true)
            val finalizedReports = mutableListOf<String>()

            BridgePollingService(PassiveEventPersistenceService(stores, json), json).awaitCompletion(
                prepared = preparedDiagnosticsScan("malformed-disposition", defaultDiagnosticsAppSettings()),
                handle = BridgeSessionHandle(bridge, "malformed-disposition", registerActiveBridge = true),
                activeScanRegistry = registry,
            ) { reportJson -> finalizedReports += reportJson }

            assertEquals(listOf(terminal), finalizedReports)
        }

    @Test
    fun `checkpoint without terminal is persisted as partial fallback`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val fixtures = checkpointFixtures(stores, backgroundScope)
            val deferredTarget = "192.168.50.2"
            val prepared =
                preparedDiagnosticsScan("session-checkpoint-fallback", defaultDiagnosticsAppSettings()).copy(
                    localNetworkDeferrals =
                        listOf(ProbeResult("tcp_connect", deferredTarget, "capability_skipped")),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueReport(
                        checkpointReport(prepared.sessionId, "Checkpoint only"),
                        ScanReportDisposition.CHECKPOINT,
                    )
                    enqueueProgress(finishedProgress(prepared.sessionId))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(
                prepared,
                BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
                rawPathRunner = ::runSettledRawPathBlock,
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertEquals("Diagnostics scan completed without a report", session.summary)
            val report = requireNotNull(session.reportJson).let(json::decodeEngineScanReportWire)
            assertEquals("Checkpoint only", report.summary)
            assertEquals(ScanCompletionKind.PARTIAL_RESULTS, report.completionKind)
            assertEquals("capability_skipped", report.results.single { it.target == deferredTarget }.outcome)
            assertTrue(
                stores.getContextsForSession(prepared.sessionId, 10).any {
                    it.contextKind == "post_runtime_restore"
                },
            )
            assertTrue(fixtures.activeScanRegistry.cancelledSessionFailures.isEmpty())
            assertEquals(1, bridge.destroyCount)
        }

    @Test
    fun `primary cleanup waits through checkpoint for delayed terminal before destroy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val fixtures = checkpointFixtures(stores, backgroundScope)
            val prepared = preparedDiagnosticsScan("session-delayed-terminal-cleanup", defaultDiagnosticsAppSettings())
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueReport(
                        checkpointReport(prepared.sessionId, "Checkpoint before cleanup"),
                        ScanReportDisposition.CHECKPOINT,
                    )
                    enqueueProgressFailure(IllegalStateException("polling interrupted"))
                    repeat(4) { enqueueReport(null) }
                    enqueueReport(
                        checkpointReport(prepared.sessionId, "Delayed terminal"),
                        ScanReportDisposition.TERMINAL,
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(
                prepared,
                BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
                rawPathRunner = ::runSettledRawPathBlock,
            )

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertEquals("Delayed terminal", session.reportJson?.let(json::decodeEngineScanReportWire)?.summary)
            assertEquals(
                "Delayed terminal",
                bridge.lastTakenReportJsonAtDestroy?.let(json::decodeEngineScanReportWire)?.summary,
            )
            assertEquals(1, bridge.destroyCount)
        }

    @Test
    fun `raw cancellation before first poll stages delayed terminal before failed settlement`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val fixtures = checkpointFixtures(stores, backgroundScope)
            val prepared = preparedDiagnosticsScan("session-cancelled-terminal", defaultDiagnosticsAppSettings())
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueReport(
                        checkpointReport(prepared.sessionId, "Cancellation checkpoint"),
                        ScanReportDisposition.CHECKPOINT,
                    )
                    enqueueProgressFailure(IllegalStateException("polling interrupted by cancellation"))
                    enqueueReport(
                        checkpointReport(prepared.sessionId, "Cancellation terminal"),
                        ScanReportDisposition.TERMINAL,
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)

            val failure =
                runCatching {
                    fixtures.coordinator.execute(
                        prepared,
                        BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge),
                        rawPathRunner = {
                            val cancellation = CancellationException("cancel-after-checkpoint")
                            throw RawPathExecutionCancelledException(
                                completedRawPathExecutionResult(
                                    executionOutcome = RawPathExecutionOutcome.BlockCancelled,
                                    executionFailure = cancellation.message,
                                ),
                                cancellation,
                            )
                        },
                    )
                }.exceptionOrNull()

            assertTrue(failure is RawPathExecutionCancelledException)
            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("failed", session.status)
            assertEquals("Cancellation terminal", session.reportJson?.let(json::decodeEngineScanReportWire)?.summary)
            assertTrue(
                stores.getContextsForSession(prepared.sessionId, 10).any {
                    it.contextKind == "post_runtime_restore"
                },
            )
            assertTrue(fixtures.activeScanRegistry.cancelledSessionFailures.isEmpty())
            assertEquals(1, bridge.destroyCount)
        }

    @Test
    fun `dns corrected reprobe waits through checkpoint for delayed terminal before destroy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val scenario = delayedDnsReprobeScenario(stores, backgroundScope)

            scenario.fixtures.coordinator.execute(
                scenario.prepared,
                BridgeSessionHandle(
                    scenario.originalBridge,
                    scenario.prepared.sessionId,
                    scenario.prepared.registerActiveBridge,
                ),
                rawPathRunner = ::runSettledRawPathBlock,
            )

            val sessions = stores.sessionsState.value
            assertTrue("Expected DNS re-probe session; sessions=$sessions", sessions.any(::isInPathSession))
            val reprobeSession = sessions.first(::isInPathSession)
            assertEquals("completed", reprobeSession.status)
            assertEquals(
                "Re-probe delayed terminal",
                reprobeSession.reportJson?.let(json::decodeEngineScanReportWire)?.summary,
            )
            assertEquals(
                "Re-probe delayed terminal",
                scenario.fixtures.bridgeFactory.bridge.lastTakenReportJsonAtDestroy
                    ?.let(json::decodeEngineScanReportWire)
                    ?.summary,
            )
            assertEquals(1, scenario.fixtures.bridgeFactory.bridge.destroyCount)
            assertTrue(stores.sessionsState.value.any { it.pathMode == ScanPathMode.IN_PATH.name })
        }

    private suspend fun delayedDnsReprobeScenario(
        stores: FakeDiagnosticsHistoryStores,
        scope: CoroutineScope,
    ): DelayedDnsReprobeScenario {
        val clock = TestDiagnosticsHistoryClock()
        val fixtures =
            executionCoordinatorFixtures(
                stores = stores,
                timelineSource = coordinatorTimelineSource(stores, scope),
                serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                json = json,
            )
        fixtures.runtimeCoordinator.updateInPathRouteLease(testCheckpointInPathRouteLease())
        val settings = checkpointDnsSettings()
        fixtures.bridgeFactory.bridge.configureDelayedReprobeReports()
        val prepared = checkpointReprobePreparedScan(settings)
        seedPreparedScan(stores, prepared)
        fixtures.activeScanRegistry.rememberPreparedScan(prepared)
        val originalBridge = buildDnsFallbackBridge(prepared.sessionId, settings)
        fixtures.activeScanRegistry.registerBridge(
            originalBridge,
            prepared.sessionId,
            prepared.registerActiveBridge,
        )
        return DelayedDnsReprobeScenario(fixtures, prepared, originalBridge)
    }

    private fun FakeNetworkDiagnosticsBridge.configureDelayedReprobeReports() {
        autoCompleteOnStart = false
        afterStartScan = {
            val reprobeSessionId = requireNotNull(startedSessionId)
            enqueueReport(
                checkpointReport(reprobeSessionId, "Re-probe checkpoint")
                    .copy(profileId = "automatic-probing", pathMode = ScanPathMode.IN_PATH),
                ScanReportDisposition.CHECKPOINT,
            )
            enqueueProgressFailure(IllegalStateException("re-probe polling interrupted"))
            repeat(4) { enqueueReport(null) }
            enqueueReport(
                checkpointReport(reprobeSessionId, "Re-probe delayed terminal")
                    .copy(profileId = "automatic-probing", pathMode = ScanPathMode.IN_PATH),
                ScanReportDisposition.TERMINAL,
            )
        }
    }

    private fun checkpointDnsSettings() =
        defaultDiagnosticsAppSettings()
            .toBuilder()
            .setDnsMode(DnsModePlainUdp)
            .setDnsProviderId(DnsProviderCustom)
            .setDnsIp("8.8.8.8")
            .build()

    private suspend fun checkpointReprobePreparedScan(settings: com.poyka.ripdpi.proto.AppSettings) =
        preparedDiagnosticsScan(
            sessionId = "session-reprobe-delayed-terminal",
            settings = settings,
            exposeProgress = false,
            registerActiveBridge = false,
            kind = ScanKind.STRATEGY_PROBE,
            profileId = "automatic-probing",
            family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
            strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
        )

    private fun testCheckpointInPathRouteLease() =
        DiagnosticsInPathRouteLease(
            runtimeId = "vpn-runtime",
            routeGeneration = 1,
            issuedRevision = 1L,
            host = "127.0.0.1",
            port = 19_080,
            credentials = DiagnosticsProxyCredentials("diagnostics", "bounded-secret"),
        )

    private fun checkpointFixtures(
        stores: FakeDiagnosticsHistoryStores,
        scope: CoroutineScope,
    ): ExecutionCoordinatorFixtures =
        executionCoordinatorFixtures(
            stores = stores,
            timelineSource = coordinatorTimelineSource(stores, scope),
            serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
            json = json,
        )

    private fun isInPathSession(session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity): Boolean =
        session.pathMode == ScanPathMode.IN_PATH.name

    private fun finishedProgress(sessionId: String) =
        ScanProgress(
            sessionId = sessionId,
            phase = "complete",
            completedSteps = 1,
            totalSteps = 1,
            message = "complete",
            isFinished = true,
        )

    private fun checkpointReport(
        sessionId: String,
        summary: String,
    ) = ScanReport(
        sessionId = sessionId,
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = summary,
    )
}

private data class DelayedDnsReprobeScenario(
    val fixtures: ExecutionCoordinatorFixtures,
    val prepared: PreparedDiagnosticsScan,
    val originalBridge: FakeNetworkDiagnosticsBridge,
)

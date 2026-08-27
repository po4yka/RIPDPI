package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeStatus
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.service.runtime.RuntimeModeProjectionStore
import com.poyka.ripdpi.service.runtime.control.DefaultRuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.ServiceControllerRuntimeControlActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRuntimeCoordinatorTest {
    @Test
    fun `published proxy endpoint without verified VPN evidence cannot issue a route lease`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val registry = DefaultServiceRuntimeRegistry()
            val session = VpnRuntimeSession("runtime-test")
            session.publishInPathLease(LocalProxyEndpoint("127.0.0.1", 18080, "user", "secret"), routeGeneration = 1L)
            registry.register(session)
            val coordinator = buildCoordinator(FakeServiceController(stateStore), stateStore, registry = registry)

            assertEquals(null, coordinator.acquireInPathRouteLease())
        }

    @Test
    fun `current verified route issues revision bound lease even without recent validated traffic`() =
        runTest {
            var nowNanos = 1L
            val evidenceStore = VpnRouteLifecycleReceiptStore { nowNanos }
            val generation = evidenceStore.beginReadyRoute()
            evidenceStore.recordForwardingOutcome(generation, "no_flow", terminal = false, revision = 1L)
            nowNanos += 120_000_000_000L
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val registry = DefaultServiceRuntimeRegistry()
            val session = VpnRuntimeSession("runtime-test")
            session.publishInPathLease(LocalProxyEndpoint("127.0.0.1", 18080, "user", "secret"), routeGeneration = 1L)
            registry.register(session)
            val coordinator =
                buildCoordinator(
                    FakeServiceController(stateStore),
                    stateStore,
                    registry = registry,
                    evidenceProvider = evidenceStore,
                )

            val initialLease = requireNotNull(coordinator.acquireInPathRouteLease())
            assertEquals(evidenceStore.capture().callbackRevision, initialLease.issuedRevision)
            assertEquals(false, evidenceStore.capture().validated)
            assertEquals("stale", evidenceStore.capture().evidenceAgeBand)
            assertTrue(coordinator.isInPathRouteLeaseCurrent(initialLease))
            assertFalse(coordinator.isInPathRouteLeaseCurrent(requireNotNull(session.diagnosticsInPathRouteLease)))

            evidenceStore.observeLost("vpn-a")
            assertEquals(null, coordinator.acquireInPathRouteLease())
            assertFalse(coordinator.isInPathRouteLeaseCurrent(initialLease))
            evidenceStore.observeReadyRouteCallbacks()
            val restoredLease = requireNotNull(coordinator.acquireInPathRouteLease())
            assertTrue(requireNotNull(restoredLease.issuedRevision) > requireNotNull(initialLease.issuedRevision))
            assertFalse(coordinator.isInPathRouteLeaseCurrent(initialLease))
            assertTrue(coordinator.isInPathRouteLeaseCurrent(restoredLease))

            evidenceStore.recordForwardingOutcome(generation, "tun_ingress_no_upstream", terminal = true, revision = 2L)
            assertFalse(coordinator.isInPathRouteLeaseCurrent(restoredLease))
            evidenceStore.recordForwardingOutcome(generation, "no_flow", terminal = false, revision = 3L)
            assertFalse(coordinator.isInPathRouteLeaseCurrent(restoredLease))
            val recoveredLease = requireNotNull(coordinator.acquireInPathRouteLease())
            assertTrue(requireNotNull(recoveredLease.issuedRevision) > requireNotNull(restoredLease.issuedRevision))
        }

    @Test
    fun `lease revoked during issuance evidence capture is not returned`() =
        runTest {
            val evidenceStore = VpnRouteLifecycleReceiptStore()
            val generation = evidenceStore.beginReadyRoute()
            val ready = evidenceStore.capture()
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val registry = DefaultServiceRuntimeRegistry()
            val session = VpnRuntimeSession("runtime-test")
            session.publishInPathLease(LocalProxyEndpoint("127.0.0.1", 18080, "user", "secret"), generation)
            registry.register(session)
            var captures = 0
            val provider =
                object : VpnRouteEvidenceProvider {
                    override val changes = MutableStateFlow(0L)

                    override fun capture(): VpnRouteEvidence {
                        captures += 1
                        if (captures == 2) session.revokeInPathLease()
                        return ready
                    }
                }
            val coordinator =
                buildCoordinator(
                    FakeServiceController(stateStore),
                    stateStore,
                    registry = registry,
                    evidenceProvider = provider,
                )

            assertEquals(null, coordinator.acquireInPathRouteLease())
            assertEquals(2, captures)
        }

    @Test
    fun `ineligible route evidence or runtime invalidates issued leases`() =
        runTest {
            val evidenceStore = VpnRouteLifecycleReceiptStore()
            evidenceStore.beginReadyRoute()
            val ready = evidenceStore.capture()
            var current = ready
            val provider =
                object : VpnRouteEvidenceProvider {
                    override val changes = MutableStateFlow(0L)

                    override fun capture() = current
                }
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val registry = DefaultServiceRuntimeRegistry()
            val session = VpnRuntimeSession("runtime-test")
            session.publishInPathLease(LocalProxyEndpoint("127.0.0.1", 18080, "user", "secret"), routeGeneration = 1L)
            registry.register(session)
            val coordinator =
                buildCoordinator(
                    FakeServiceController(stateStore),
                    stateStore,
                    registry = registry,
                    evidenceProvider = provider,
                )
            val issued = requireNotNull(coordinator.acquireInPathRouteLease())
            val lifecycle = requireNotNull(ready.lifecycle)
            val invalidEvidence =
                listOf(
                    ready.copy(lifecycle = lifecycle.copy(state = VpnRouteLifecycleState.Established)),
                    ready.copy(lifecycle = lifecycle.copy(generation = lifecycle.generation + 1L)),
                    ready.copy(callbackState = VpnRouteCallbackState.Awaiting),
                    ready.copy(callbackRevision = null),
                    ready.copy(ownerVerification = VpnRouteOwnerVerification.Unavailable),
                    ready.copy(routeConsistency = VpnRouteConsistency.Mismatch),
                    ready.copy(forwardingTerminal = true),
                )
            for (invalid in invalidEvidence) {
                current = invalid
                assertEquals(null, coordinator.acquireInPathRouteLease())
                assertFalse(coordinator.isInPathRouteLeaseCurrent(issued))
            }
            current = ready
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertEquals(null, coordinator.acquireInPathRouteLease())
            assertFalse(coordinator.isInPathRouteLeaseCurrent(issued))
            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            assertEquals(null, coordinator.acquireInPathRouteLease())
            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            session.revokeInPathLease()
            assertFalse(coordinator.isInPathRouteLeaseCurrent(issued))
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `concurrent raw path scans resume only after the last scan finishes`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()

            val first =
                async {
                    coordinator.runRawPathScan {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
            firstEntered.await()
            val second =
                async {
                    coordinator.runRawPathScan {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
            secondEntered.await()

            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)

            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
            assertEquals(0, controller.startCount)
            assertFalse(first.isCompleted)

            releaseSecond.complete(Unit)
            first.await()
            second.await()
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
            assertEquals(1, controller.startCount)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `concurrent raw path participants await shared execution settlement`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()

            val first =
                async {
                    coordinator.runRawPathScan {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
            firstEntered.await()
            val second =
                async {
                    coordinator.runRawPathScan {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
            secondEntered.await()

            releaseFirst.complete(Unit)
            runCurrent()

            assertFalse("First participant must wait for shared settlement", first.isCompleted)

            releaseSecond.complete(Unit)
            val firstResult = first.await()
            val secondResult = second.await()
            val firstSettlement = firstResult.settlement
            val secondSettlement = secondResult.settlement

            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
            assertEquals(1, controller.startCount)
            assertEquals(RawPathExecutionOutcome.Completed, firstResult.executionOutcome)
            assertEquals(RawPathExecutionOutcome.Completed, secondResult.executionOutcome)
            assertEquals(firstSettlement, secondSettlement)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, firstSettlement.outcome)
            assertEquals(1L, firstSettlement.rawWindowGeneration)
            assertEquals(0L, firstSettlement.resumeIntentGeneration)
        }

    @Test
    fun `entry stop failure settles raw path window before block runs`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    stopFailure = IOException("stop failed")
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            var blockRan = false

            val result =
                coordinator.runRawPathScan {
                    blockRan = true
                }

            assertEquals(RawPathExecutionOutcome.EntryFailed, result.executionOutcome)
            assertEquals("stop failed", result.executionFailure)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertFalse(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)
        }

    @Test
    fun `block failure returns observable settlement receipt`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val result =
                coordinator.runRawPathScan {
                    throw IOException("scan failed")
                }

            assertEquals(RawPathExecutionOutcome.BlockFailed, result.executionOutcome)
            assertEquals("scan failed", result.executionFailure)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(1L, result.settlement.rawWindowGeneration)
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `entry failure after halted stop restores runtime before returning receipt`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    stopAfterTransitionFailure = IOException("after halt failed")
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val result = coordinator.runRawPathScan {}

            assertEquals(RawPathExecutionOutcome.EntryFailed, result.executionOutcome)
            assertEquals("after halt failed", result.executionFailure)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `block cancellation throws typed cancellation with observable settlement receipt`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val scan =
                async {
                    coordinator.runRawPathScan {
                        throw CancellationException("scan cancelled")
                    }
                }
            val error = runCatching { scan.await() }.exceptionOrNull()
            val cancellation = error as RawPathExecutionCancelledException
            val result = cancellation.result

            assertTrue(scan.isCancelled)
            assertEquals(RawPathExecutionOutcome.BlockCancelled, result.executionOutcome)
            assertEquals("scan cancelled", result.executionFailure)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `entry cancellation keeps child job cancelled with observable settlement receipt`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    stopAfterTransitionFailure = CancellationException("entry cancelled")
                }
            val settings =
                FakeCoordinatorSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDiagnosticsAutoResumeAfterRawScan(true)
                        .build(),
                )
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 50,
                    waitDelayMs = 1,
                )

            val scan = async { coordinator.runRawPathScan {} }
            val error = runCatching { scan.await() }.exceptionOrNull()
            val cancellation = error as RawPathExecutionCancelledException
            val result = cancellation.result

            assertTrue(scan.isCancelled)
            assertEquals(RawPathExecutionOutcome.EntryCancelled, result.executionOutcome)
            assertEquals("entry cancelled", result.executionFailure)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }
}

class DiagnosticsRuntimeCoordinatorSettlementTest {
    @Test
    fun `resume start failure records restore failed after raw path scan block completes`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    startFailure = IOException("resume failed")
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            var blockRan = false

            val result =
                coordinator.runRawPathScan {
                    blockRan = true
                }
            val settlement = result.settlement

            assertTrue(blockRan)
            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
            assertEquals(RawPathExecutionSettlementOutcome.RestoreFailed, settlement.outcome)
            assertEquals("resume failed", settlement.restoreFailure)
            assertEquals(1L, settlement.rawWindowGeneration)
            assertEquals(0L, settlement.resumeIntentGeneration)
            assertEquals(RawPathRuntimeStatus.Halted, settlement.postRuntimeContext.status)
        }

    @Test
    fun `entry timeout waiting for halted state settles raw path window before block runs`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    transitionOnStop = false
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val result = coordinator.runRawPathScan {}

            assertEquals(RawPathExecutionOutcome.EntryFailed, result.executionOutcome)
            assertTrue(result.executionFailure?.contains("Timed out waiting for service status Halted") == true)
            assertEquals(RawPathExecutionSettlementOutcome.RestoreFailed, result.settlement.outcome)
            assertTrue(
                "Unexpected restore failure: ${result.settlement.restoreFailure}",
                result.settlement.restoreFailure?.contains("Timed out waiting for service status Halted") == true,
            )
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancellation while entering raw path window resumes an accepted runtime pause`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    delayDiagnosticsStopTransition = true
                }
            val settings = FakeCoordinatorSettingsRepository()
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 50,
                    waitDelayMs = 1_000,
                )

            val scan = async { coordinator.runAutomaticRawPathScan {} }
            runCurrent()
            assertEquals(1, controller.stopCount)

            val cancellation = async { scan.cancelAndJoin() }
            runCurrent()
            assertEquals(0, controller.startCount)

            controller.completeDelayedDiagnosticsStopTransition()
            runCurrent()
            cancellation.await()

            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `entry timeout waits for delayed stop before diagnostics start`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    delayDiagnosticsStopTransition = true
                }
            val settings =
                FakeCoordinatorSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDiagnosticsAutoResumeAfterRawScan(true)
                        .build(),
                )
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 2,
                    waitDelayMs = 1,
                )

            val scan = async { coordinator.runRawPathScan {} }
            advanceTimeBy(2)
            runCurrent()

            assertFalse("Receipt must wait for delayed stop convergence", scan.isCompleted)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)

            controller.completeDelayedDiagnosticsStopTransition()
            advanceTimeBy(1)
            val result = scan.await()

            assertEquals(RawPathExecutionOutcome.EntryFailed, result.executionOutcome)
            assertTrue(result.executionFailure?.contains("Timed out waiting for service status Halted") == true)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, result.settlement.postRuntimeContext.status)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `entry timeout rechecks user stop ownership after delayed stop converges`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    delayDiagnosticsStopTransition = true
                }
            val settings =
                FakeCoordinatorSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDiagnosticsAutoResumeAfterRawScan(true)
                        .build(),
                )
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 2,
                    waitDelayMs = 1,
                )

            val scan = async { coordinator.runRawPathScan {} }
            advanceTimeBy(2)
            runCurrent()

            controller.stop()
            advanceTimeBy(1)
            val result = scan.await()

            assertEquals(RawPathExecutionOutcome.EntryFailed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.SupersededByUserStop, result.settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Halted, result.settlement.postRuntimeContext.status)
            assertEquals(2, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `timeout waiting for resumed running state records restore failed after block`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    transitionOnStart = false
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            var blockRan = false

            val result =
                coordinator.runRawPathScan {
                    blockRan = true
                }
            val settlement = result.settlement

            assertTrue(blockRan)
            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(RawPathExecutionSettlementOutcome.RestoreFailed, settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Halted, settlement.postRuntimeContext.status)
            assertTrue(
                "Unexpected resume failure: ${settlement.restoreFailure}",
                settlement.restoreFailure?.contains("Timed out waiting for service status Running") == true,
            )
        }

    @Test
    fun `rejected resume start skips running wait after raw path scan block`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    nextStartResult =
                        ServiceStartResult.Rejected(
                            mode = Mode.VPN,
                            reason = ServiceStartRejectionReason.VpnConsentMissing,
                        )
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            var blockRan = false

            coordinator.runRawPathScan {
                blockRan = true
            }

            assertTrue(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `automatic raw path scan always resumes running service even when user auto resume is disabled`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(false)
                            .build(),
                    ),
                )
            var blockRan = false

            coordinator.runAutomaticRawPathScan {
                blockRan = true
                assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
            }

            assertTrue(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `settled raw path scan returns restored receipt with generations`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val result = coordinator.runRawPathScan {}
            val settlement = result.settlement

            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, settlement.outcome)
            assertEquals(1L, settlement.rawWindowGeneration)
            assertEquals(0L, settlement.resumeIntentGeneration)
            assertTrue(settlement.runtimeWasRunning)
            assertTrue(settlement.resumeRequired)
            assertEquals(RawPathRuntimeStatus.Running, settlement.postRuntimeContext.status)
            assertEquals(Mode.VPN, settlement.postRuntimeContext.mode)
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `settled raw path scan returns restore not required when runtime starts halted`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Halted to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator = buildCoordinator(controller, stateStore)

            val result = coordinator.runRawPathScan {}
            val settlement = result.settlement

            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.RestoreNotRequired, settlement.outcome)
            assertEquals(1L, settlement.rawWindowGeneration)
            assertEquals(0L, settlement.resumeIntentGeneration)
            assertFalse(settlement.runtimeWasRunning)
            assertFalse(settlement.resumeRequired)
            assertEquals(RawPathRuntimeStatus.Halted, settlement.postRuntimeContext.status)
            assertEquals(0, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `settled raw path scan returns restore policy disabled when auto resume is disabled`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(false)
                            .build(),
                    ),
                )

            val result = coordinator.runRawPathScan {}
            val settlement = result.settlement

            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.RestorePolicyDisabled, settlement.outcome)
            assertTrue(settlement.runtimeWasRunning)
            assertFalse(settlement.resumeRequired)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }
}

class DiagnosticsRuntimeCoordinatorIntentRaceTest {
    @Test
    fun `settled raw path scan records user stop superseded restore`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val result =
                coordinator.runRawPathScan {
                    controller.stop()
                }
            val settlement = result.settlement

            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.SupersededByUserStop, settlement.outcome)
            assertEquals(1L, settlement.rawWindowGeneration)
            assertEquals(0L, settlement.resumeIntentGeneration)
            assertEquals(RawPathRuntimeStatus.Halted, settlement.postRuntimeContext.status)
            assertEquals(2, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restored receipt is not returned before runtime reaches running`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore).apply { transitionOnStart = false }
            val settings =
                FakeCoordinatorSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDiagnosticsAutoResumeAfterRawScan(true)
                        .build(),
                )
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 50,
                    waitDelayMs = 1,
                )

            val scan = async { coordinator.runRawPathScan {} }
            runCurrent()

            assertFalse(scan.isCompleted)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)

            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            val result = scan.await()
            val settlement = result.settlement

            assertEquals(RawPathExecutionOutcome.Completed, result.executionOutcome)
            assertEquals(RawPathExecutionSettlementOutcome.Restored, settlement.outcome)
            assertEquals(RawPathRuntimeStatus.Running, settlement.postRuntimeContext.status)
        }

    @Test
    fun `raw path generation advances only after prior settlement finishes`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            val first = coordinator.runRawPathScan {}.settlement
            val second = coordinator.runRawPathScan {}.settlement

            assertNotEquals(first.rawWindowGeneration, second.rawWindowGeneration)
            assertEquals(first.rawWindowGeneration + 1L, second.rawWindowGeneration)
            assertEquals(first.resumeIntentGeneration, second.resumeIntentGeneration)
        }

    @Test
    fun `explicit user stop during raw path scan suppresses diagnostics resume`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {
                controller.stop()
            }

            assertEquals(2, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `user stop racing diagnostics resume remains the final operation`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.afterDiagnosticsStart = controller::stop
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(
                listOf("diagnostics-stop", "diagnostics-start", "user-stop", "diagnostics-stop"),
                controller.operations,
            )
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `user stop immediately before diagnostics resume is compensated`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.beforeDiagnosticsStart = controller::stop
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(
                listOf("diagnostics-stop", "user-stop", "diagnostics-start", "diagnostics-stop"),
                controller.operations,
            )
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `accepted stop captured with stale running status suppresses resume`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.runtimeResumeIntentTracker.recordAcceptedStop()
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(listOf("diagnostics-stop"), controller.operations)
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `newer user start suppresses stale stop compensation`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {
                controller.stop()
                controller.start(Mode.Proxy)
            }

            assertEquals(listOf("diagnostics-stop", "user-stop", "user-start"), controller.operations)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `automatic raw path cleanup waits for newer user start readiness`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore).apply { transitionOnStart = false }
            val settings = FakeCoordinatorSettingsRepository()
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    vpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
                    waitAttempts = 50,
                    waitDelayMs = 1,
                )

            val scan =
                async {
                    coordinator.runAutomaticRawPathScan {
                        controller.start(Mode.Proxy)
                    }
                }
            runCurrent()
            val beforeReady = scan.isCompleted to stateStore.status.value.first

            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            scan.await()

            assertEquals(
                (false to AppStatus.Halted) to (true to AppStatus.Running),
                beforeReady to (scan.isCompleted to stateStore.status.value.first),
            )
        }
}

private fun buildCoordinator(
    controller: FakeServiceController,
    stateStore: FakeCoordinatorStateStore,
    settings: AppSettingsRepository = FakeCoordinatorSettingsRepository(),
    registry: ServiceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
    evidenceProvider: VpnRouteEvidenceProvider = VpnRouteLifecycleReceiptStore(),
): DefaultDiagnosticsRuntimeCoordinator =
    DefaultDiagnosticsRuntimeCoordinator(
        runtimeControlPlane =
            DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
        runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
        serviceStateStore = stateStore,
        appSettingsRepository = settings,
        runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
        serviceController = controller,
        serviceRuntimeRegistry = registry,
        vpnRouteEvidenceProvider = evidenceProvider,
        waitAttempts = 2,
        waitDelayMs = 0,
    )

private class FakeCoordinatorSettingsRepository(
    initial: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class FakeCoordinatorStateStore(
    initial: Pair<AppStatus, Mode>,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initial)
    private val eventState = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState
    override val events: SharedFlow<ServiceEvent> = eventState
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(
        sender: com.poyka.ripdpi.data.Sender,
        reason: FailureReason,
    ) = Unit

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

private class FakeServiceController(
    private val stateStore: FakeCoordinatorStateStore,
) : ServiceController {
    val runtimeResumeIntentTracker = RuntimeResumeIntentTracker()
    var stopFailure: Throwable? = null
    var stopAfterTransitionFailure: Throwable? = null
    var startFailure: Throwable? = null
    var transitionOnStop: Boolean = true
    var transitionOnStart: Boolean = true
    var delayDiagnosticsStopTransition: Boolean = false
    var stopCount: Int = 0
    var startCount: Int = 0
    var nextStartResult: ServiceStartResult? = null
    var beforeDiagnosticsStart: (() -> Unit)? = null
    var afterDiagnosticsStart: (() -> Unit)? = null
    val operations = mutableListOf<String>()
    private var pendingDiagnosticsStopTransition: Boolean = false

    override fun start(mode: Mode): ServiceStartResult =
        runtimeResumeIntentTracker.withUserStart(
            action = { start(mode, "user-start") },
            isAccepted = { it is ServiceStartResult.Accepted },
        )

    override fun startForDiagnostics(mode: Mode): ServiceStartResult {
        beforeDiagnosticsStart?.invoke()
        val result = start(mode, "diagnostics-start")
        afterDiagnosticsStart?.invoke()
        return result
    }

    private fun start(
        mode: Mode,
        operation: String,
    ): ServiceStartResult {
        operations += operation
        startCount += 1
        startFailure?.let { throw it }
        nextStartResult?.let { return it }
        if (transitionOnStart) {
            stateStore.setStatus(AppStatus.Running, mode)
        }
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() {
        runtimeResumeIntentTracker.recordAcceptedStop()
        stop("user-stop")
    }

    override fun stopForDiagnostics() {
        stop("diagnostics-stop")
    }

    private fun stop(operation: String) {
        operations += operation
        stopCount += 1
        throwStopFailureIfConfigured()
        if (operation == "diagnostics-stop" && delayDiagnosticsStopTransition) {
            pendingDiagnosticsStopTransition = true
            throwStopAfterTransitionFailureIfConfigured()
            return
        }
        if (transitionOnStop) {
            stateStore.setStatus(AppStatus.Halted, stateStore.status.value.second)
        }
        throwStopAfterTransitionFailureIfConfigured()
    }

    private fun throwStopFailureIfConfigured() {
        stopFailure?.let { throw it }
    }

    private fun throwStopAfterTransitionFailureIfConfigured() {
        stopAfterTransitionFailure?.let { throw it }
    }

    fun completeDelayedDiagnosticsStopTransition() {
        if (!pendingDiagnosticsStopTransition) {
            return
        }
        pendingDiagnosticsStopTransition = false
        if (transitionOnStop) {
            stateStore.setStatus(AppStatus.Halted, stateStore.status.value.second)
        }
    }
}

private fun VpnRouteLifecycleReceiptStore.beginReadyRoute(): Long {
    val generation =
        beginIntended(
            ipv6Enabled = false,
            dns = "1.1.1.1",
            appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
            ownPackage = "com.poyka.ripdpi",
            networkParameters = VpnTunnelNetworkParameters(),
            apiLevel = Build.VERSION_CODES.Q,
        )
    markEstablished(generation)
    markBridgeReady(generation)
    observeReadyRouteCallbacks()
    return generation
}

private fun VpnRouteLifecycleReceiptStore.observeReadyRouteCallbacks() {
    observeCapabilities(
        networkKey = "vpn-a",
        hasInternet = true,
        validated = false,
        captivePortal = false,
        ownerVerification = VpnRouteOwnerVerification.Verified,
    )
    observeDefaultRoutes("vpn-a", setOf(VpnRouteFamilyIpv4))
}

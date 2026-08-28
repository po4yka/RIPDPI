package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.HandoffOutcome
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.TunnelUpstream
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.core.testing.FakeManagedTunnel
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connect-flow controller tests (decisions 2-4): protect-first ordering, no
 * half-session on failure, secret-free snapshot, handover, and the native
 * selection leaving the Xray path untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class XrayProviderSessionTestFixture {
    protected val bridge = FakeXrayNativeBridge()
    protected val owner = XrayRuntimeOwner(bridge, Dispatchers.Unconfined)
    protected val tunnel = FakeManagedTunnel()
    protected val selectionStore = FakeSelectionStore()
    protected val profileStore = FakeDurableXrayProfileStore()
    protected val startParamsHolder = XrayTunnelStartParamsHolder()
    protected val renderedConfig = arrayOfNulls<String>(1)
    protected var protectDetail: String? = null

    protected val profile =
        XrayProfile(
            name = "Tokyo",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 8443,
                    uuid = "11111111-2222-3333-4444-555555555555",
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                        ),
                ),
        )

    protected fun controller(recoverPendingProfileMutations: suspend () -> Unit = {}): XrayProviderSessionController {
        val orchestrator =
            XrayProviderOrchestrator(
                // Run the runtime's blocking native stop inline instead of on the
                // production Dispatchers.IO default; a real IO worker leaks past
                // runTest's scheduler and flakily trips UncompletedCoroutinesError on
                // the teardown path (matches XrayProviderOrchestratorTest).
                xrayRuntimeFactory = { cfg ->
                    RipDpiXrayRuntime(owner, cfg)
                },
                tunnel = tunnel,
                protectController = { true },
                renderedConfigProvider = { checkNotNull(renderedConfig[0]) },
            )
        return XrayProviderSessionController(
            readSelectedProfile = {
                recoverPendingProfileMutations()
                val selection = selectionStore.current()
                XraySelectedProfile(
                    selection,
                    if (selection.kind ==
                        com.poyka.ripdpi.data.xray.VpnProviderKind.Xray
                    ) {
                        profileStore.load(selection.activeProfileId)
                    } else {
                        null
                    },
                )
            },
            routeBuilder = XrayProviderRouteBuilder(resolveEndpoint = { listOf("192.0.2.1") }),
            orchestrator = orchestrator,
            snapshotDeriver = XrayProviderSnapshotDeriver(clock = { 1L }),
            probeRunner = XrayProviderDiagnosticsProbeRunner(),
            startParamsHolder = startParamsHolder,
            runtimeOwner = owner,
            renderedConfigSink = { renderedConfig[0] = it },
            lastProtectFailureDetail = { protectDetail },
        )
    }

    protected fun params(): XrayTunnelStartParams =
        XrayTunnelStartParams(
            activeDns =
                ActiveDnsSettings(
                    mode = DnsModePlainUdp,
                    providerId = "custom",
                    dnsIp = "1.1.1.1",
                    encryptedDnsProtocol = "",
                    encryptedDnsHost = "",
                    encryptedDnsPort = 0,
                    encryptedDnsTlsServerName = "",
                    encryptedDnsBootstrapIps = emptyList(),
                    encryptedDnsDohUrl = "",
                    encryptedDnsDnscryptProviderName = "",
                    encryptedDnsDnscryptPublicKey = "",
                ),
            overrideReason = null,
            logContext = null,
            forceTunnelDns = false,
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class XrayProviderSessionControllerTest : XrayProviderSessionTestFixture() {
    @Test
    fun `durable selection reads wait for pending profile mutation recovery`() =
        runTest {
            val callOrder = mutableListOf<String>()
            selectionStore.onCurrent = { callOrder += "selection" }
            val controller = controller { callOrder += "recover" }

            controller.start(params())
            assertEquals(listOf("recover", "selection"), callOrder)

            callOrder.clear()
            controller.restart(params())
            assertEquals(listOf("recover", "selection"), callOrder)
        }

    @Test
    fun `delegate uses one durable selection snapshot for each lifecycle operation`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            var reads = 0
            selectionStore.onCurrent = { reads += 1 }
            val delegate =
                XrayConnectFlowDelegate(
                    controller = controller(),
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            assertTrue(delegate.tryStart(VpnRuntimeSession(), sampleResolution()))
            assertEquals(1, reads)
            reads = 0
            assertTrue(delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 2L))
            assertEquals(1, reads)
            reads = 0
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Native, null))
            assertFalse(delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 3L))
            assertEquals(1, reads)
        }

    @Test
    fun `runtime config and labels use one selected profile snapshot`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            var loads = 0
            profileStore.onLoad = {
                loads += 1
                if (loads == 2) profileStore.save("default", profile.copy(name = "Replacement"))
            }
            val ctrl = controller()

            assertTrue(ctrl.start(params()) is HandoffOutcome.Running)

            assertEquals(profile.name, ctrl.currentSnapshot().profileName)
            assertEquals(1, loads)
        }

    @Test
    fun `start protect-first then tunnel, leaving a clean running session`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)

            val outcome = controller().start(params())

            assertTrue(outcome is HandoffOutcome.Running)
            // Protect registered BEFORE start, and start BEFORE the tunnel points
            // at the inbound.
            val registerIdx = bridge.callLog.indexOf("registerProtect")
            val startIdx = bridge.callLog.indexOf("start")
            assertTrue(registerIdx >= 0 && registerIdx < startIdx)
            assertEquals(1, tunnel.startCount)
            assertTrue(tunnel.lastUpstream is TunnelUpstream.Xray)
            // Secret config cleared right after start.
            assertEquals(null, renderedConfig[0])
        }

    @Test
    fun `no half-session when no profile is persisted`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            // No profile saved.
            val ctrl = controller()
            val outcome = ctrl.start(params())
            assertEquals(XrayProviderFailureClass.ConfigInvalid, ctrl.currentSnapshot().failureClass)
            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, bridge.startCount)
            assertEquals(0, tunnel.startCount)
        }

    @Test
    fun `semantic validation rejection is visible as a typed config failure`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile.copy(outbound = profile.outbound.copy(serverPort = 0)))
            val ctrl = controller()

            assertTrue(ctrl.start(params()) is HandoffOutcome.Failed)

            assertEquals(XrayProviderFailureClass.ConfigInvalid, ctrl.currentSnapshot().failureClass)
            assertTrue(ctrl.currentSnapshot().hasConfigErrors)
            assertEquals(0, bridge.startCount)
        }

    @Test
    fun `config-rejected start produces a provider-failed snapshot, no engine start`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "bad"))
            profileStore.save("bad", profile.copy(outbound = profile.outbound.copy(flow = "")))

            val ctrl = controller()
            val outcome = ctrl.start(params())

            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, bridge.startCount)
            val snapshot = ctrl.currentSnapshot()
            assertEquals(XrayProviderFailureClass.ConfigInvalid, snapshot.failureClass)
            assertTrue(snapshot.hasConfigErrors)
        }

    @Test
    fun `tunnel failure tears the engine back down (no half session)`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            tunnel.failOnStart = true

            val ctrl = controller()
            val outcome = ctrl.start(params())

            assertTrue(outcome is HandoffOutcome.Failed)
            // Engine was started then stopped to avoid a half session.
            assertTrue(bridge.startCount >= 1)
            assertTrue(bridge.stopCount >= 1)
            assertEquals(VpnProviderState.Stopped, ctrl.providerState)
        }

    @Test
    fun `currentSnapshot never contains a secret`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())

            val snapshot = ctrl.currentSnapshot()
            val serialized = snapshot.toString()
            assertFalse(serialized.contains("11111111-2222-3333"))
            assertFalse(serialized.contains("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
            assertFalse(serialized.contains("edge.example.com"))
            // Profile name is a safe label and may appear.
            assertEquals("Tokyo", snapshot.profileName)
        }

    @Test
    fun `protect failure detail is redacted into the snapshot`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            protectDetail = "protect failed for 11111111-2222-3333-4444-555555555555"
            val ctrl = controller()
            ctrl.start(params())

            val snapshot = ctrl.currentSnapshot()
            assertEquals(XrayProviderFailureClass.ProtectFailure, snapshot.failureClass)
            val detail = snapshot.lastFailureDetailRedacted!!
            assertTrue(detail.contains(XrayProfileRedactor.REDACTED))
            assertFalse(detail.contains("11111111-2222-3333"))
        }

    @Test
    fun `native selection makes the controller a no-op (native path untouched)`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Native, null))
            val ctrl = controller()
            val outcome = ctrl.start(params())
            assertEquals(HandoffOutcome.Stopped, outcome)
            assertFalse(ctrl.isActive)
            assertEquals(0, bridge.startCount)
            assertEquals(0, tunnel.startCount)
        }

    @Test
    fun `delegate retains provider ownership after incomplete cleanup`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            delegate.tryStart(VpnRuntimeSession(), sampleResolution())
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw
            assertTrue(runCatching { delegate.tryStop() }.isFailure)
            assertTrue(delegate.ownsActiveProviderPath)
            assertTrue(startParamsHolder.current != null)
            assertEquals(0, tunnel.stopCount)
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Clean
            assertTrue(delegate.tryStop())
            assertFalse(delegate.ownsActiveProviderPath)
            assertEquals(null, startParamsHolder.current)
        }

    @Test
    fun `new service cannot bypass occupied Xray lease by selecting native`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val original = controller()
            original.start(params())
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw
            original.stop()
            original.revokeProtection()
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Native, null))
            val replacement =
                XrayConnectFlowDelegate(
                    controller = controller(),
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            assertTrue(runCatching { replacement.tryStart(VpnRuntimeSession(), sampleResolution()) }.isFailure)
            assertFalse(checkNotNull(bridge.registeredProtectController).protect(42))
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Clean
            original.stop()
            assertFalse(replacement.tryStart(VpnRuntimeSession(), sampleResolution()))
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class XrayProviderFailureGenerationTest : XrayProviderSessionTestFixture() {
    @Test
    fun `telemetry failure stop guard becomes stale after replacement generation`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            val session = VpnRuntimeSession()
            val dependencies =
                XrayFailureTestDependencies(
                    TestVpnServiceHost(backgroundScope),
                    StandardTestDispatcher(testScheduler),
                    ctrl,
                )
            val state = XrayFailureTestState(session)
            val guards = mutableListOf<RuntimeStopGuard>()
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: com.poyka.ripdpi.data.ServiceStatus,
                        failureReason: com.poyka.ripdpi.data.FailureReason?,
                    ) = error("unguarded failure publication")

                    override suspend fun failAndStopService(
                        failureReason: com.poyka.ripdpi.data.FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean = error("unexpected generic failure publication")

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean {
                        guards += checkNotNull(guard)
                        return true
                    }
                }
            val handler = VpnTelemetryFailureHandler(dependencies, state, callbacks)
            bridge.aliveDuringStartup = false
            assertEquals(VpnTelemetryFailureHandling.StopAccepted, handler.handleOutcome(emptyXrayFailureTelemetry()))
            bridge.aliveDuringStartup = true
            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))
            ctrl.restart(params())
            runCurrent()
            assertFalse(guards.single().isCurrent())
            bridge.aliveDuringStartup = false
            assertEquals(VpnTelemetryFailureHandling.StopAccepted, handler.handleOutcome(emptyXrayFailureTelemetry()))
            assertEquals(2, guards.size)
            assertTrue(guards.last().isCurrent())
            ctrl.stop()
        }

    @Test
    fun `failed generation keeps telemetry polling when guarded stop is rejected`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            val dependencies =
                XrayFailureTestDependencies(
                    TestVpnServiceHost(backgroundScope),
                    StandardTestDispatcher(testScheduler),
                    ctrl,
                )
            val state = XrayFailureTestState(VpnRuntimeSession())
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) = error("unguarded failure publication")

                    override suspend fun failAndStopService(
                        failureReason: FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean = error("unexpected generic failure publication")

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean {
                        checkNotNull(guard)
                        return false
                    }
                }
            val handler = VpnTelemetryFailureHandler(dependencies, state, callbacks)

            bridge.aliveDuringStartup = false

            assertEquals(VpnTelemetryFailureHandling.DiscardStale, handler.handleOutcome(emptyXrayFailureTelemetry()))
        }

    @Test
    fun `generic tunnel failure uses pre-poll Xray generation guard`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            val state = XrayFailureTestState(VpnRuntimeSession())
            val boundary = VpnTelemetryFailureBoundary.capture(state, ctrl)
            val guardedStops = mutableListOf<RuntimeStopGuard?>()
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) = error("unguarded failure publication")

                    override suspend fun failAndStopService(
                        failureReason: FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean {
                        guardedStops += guard
                        val accepted = guard?.isCurrent() == true
                        if (accepted) beforeFailureStatus()
                        return accepted
                    }

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean =
                        error("generic failure must use failAndStopService")
                }
            val handler =
                VpnTelemetryFailureHandler(
                    GenericTunnelFailureTestDependencies(
                        host = TestVpnServiceHost(backgroundScope),
                        xrayController = ctrl,
                    ),
                    state,
                    callbacks,
                )

            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))
            ctrl.restart(params())

            assertEquals(
                VpnTelemetryFailureHandling.DiscardStale,
                handler.handleOutcome(genericTunnelFailureTelemetry(), boundary),
            )
            assertFalse(checkNotNull(guardedStops.single()).isCurrent())
        }

    @Test
    fun `generic tunnel failure captured before Xray start cannot stop new provider generation`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val state = XrayFailureTestState(VpnRuntimeSession())
            val boundary = VpnTelemetryFailureBoundary.capture(state, ctrl)
            val guardedStops = mutableListOf<RuntimeStopGuard?>()
            var failureTelemetryReported = false
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) = error("unguarded failure publication")

                    override suspend fun failAndStopService(
                        failureReason: FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean {
                        guardedStops += guard
                        val accepted = guard?.isCurrent() == true
                        if (accepted) {
                            beforeFailureStatus()
                            failureTelemetryReported = true
                        }
                        return accepted
                    }

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean =
                        error("generic failure must use failAndStopService")
                }
            val handler =
                VpnTelemetryFailureHandler(
                    GenericTunnelFailureTestDependencies(
                        host = TestVpnServiceHost(backgroundScope),
                        xrayController = ctrl,
                    ),
                    state,
                    callbacks,
                )

            ctrl.start(params())

            assertEquals(
                VpnTelemetryFailureHandling.DiscardStale,
                handler.handleOutcome(genericTunnelFailureTelemetry(), boundary),
            )
            assertFalse(checkNotNull(guardedStops.single()).isCurrent())
            assertFalse(failureTelemetryReported)
        }

    @Test
    fun `protect failure uses event Xray generation guard`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            val monitor = TestVpnProtectFailureMonitor()
            val state = XrayFailureTestState(VpnRuntimeSession())
            val guardedStops = mutableListOf<RuntimeStopGuard?>()
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) = error("unguarded failure publication")

                    override suspend fun failAndStopService(
                        failureReason: FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean {
                        guardedStops += guard
                        val accepted = guard?.isCurrent() == true
                        if (accepted) beforeFailureStatus()
                        return accepted
                    }

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean =
                        error("protect failure must use failAndStopService")
                }
            val watcher =
                VpnProtectFailureWatcher(
                    ProtectFailureTestDependencies(
                        host = TestVpnServiceHost(backgroundScope),
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                        xrayController = ctrl,
                        vpnProtectFailureMonitor = monitor,
                    ),
                    state,
                    callbacks,
                )

            watcher.start()
            runCurrent()
            val failedGeneration = checkNotNull(ctrl.currentGenerationIfActive())
            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))
            ctrl.restart(params())
            monitor.report(
                VpnProtectFailureEvent(
                    fd = 42,
                    reason = FailureReason.PermissionLost("VPN"),
                    detail = "stale xray protect failure",
                    detectedAt = 2L,
                    providerGeneration = failedGeneration,
                ),
            )
            runCurrent()

            assertFalse(checkNotNull(guardedStops.single()).isCurrent())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class XrayProviderSessionRestartTest : XrayProviderSessionTestFixture() {
    @Test
    fun `stop tears down and clears the staged config`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            ctrl.stop()
            assertEquals(VpnProviderState.Stopped, ctrl.providerState)
            assertEquals(null, renderedConfig[0])
            assertEquals(null, startParamsHolder.current)
        }

    @Test
    fun `restart performs a dual restart of engine and tunnel`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            ctrl.start(params())
            val firstStarts = bridge.startCount

            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))

            val outcome = ctrl.restart(params())
            assertTrue(outcome is HandoffOutcome.Running)
            assertTrue(bridge.startCount > firstStarts)
            assertTrue(tunnel.startCount >= 2)
        }

    @Test
    fun `restart params use the incoming policy context before session commit`() {
        val session = VpnRuntimeSession(runtimeId = "runtime")
        val resolution = sampleResolution(policySignature = "new-policy", networkScopeKey = "new-network")

        val startParams = defaultXrayStartParams(session, resolution)

        assertEquals("new-policy", startParams.logContext?.policySignature)
        assertEquals("new-network", startParams.logContext?.fingerprintHash)
    }

    @Test
    fun `restart recovery failure leaves the active session untouched`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            var recoveryFailure: Throwable? = null
            val ctrl = controller { recoveryFailure?.let { throw it } }
            val initialParams = params()
            assertTrue(ctrl.start(initialParams) is HandoffOutcome.Running)
            val stopsBeforeRestart = bridge.stopCount
            recoveryFailure = IllegalStateException("recovery failed")

            val failure = runCatching { ctrl.restart(params()) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(stopsBeforeRestart, bridge.stopCount)
            assertTrue(ctrl.isActive)
            assertEquals(initialParams, startParamsHolder.current)
        }

    @Test
    fun `restart recovery cancellation leaves the active session untouched`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            var cancelRecovery = false
            val ctrl =
                controller {
                    if (cancelRecovery) throw CancellationException("handover cancelled")
                }
            assertTrue(ctrl.start(params()) is HandoffOutcome.Running)
            val stopsBeforeRestart = bridge.stopCount
            cancelRecovery = true

            val failure = runCatching { ctrl.restart(params()) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(stopsBeforeRestart, bridge.stopCount)
            assertTrue(ctrl.isActive)
        }

    @Test
    fun `restart cancellation before route commit leaves controller and delegate active`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            assertTrue(delegate.tryStart(VpnRuntimeSession(), sampleResolution()))
            profileStore.onLoad = { throw CancellationException("profile load cancelled") }

            val failure =
                runCatching {
                    delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 2L)
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(delegate.isActive)
            assertTrue(delegate.ownsActiveProviderPath)
            assertTrue(ctrl.isActive)
            assertEquals(VpnProviderState.Running, ctrl.providerState)
            assertEquals(null, renderedConfig[0])

            profileStore.onLoad = {}
            assertTrue(delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 3L))
            assertTrue(delegate.isActive)
        }

    @Test
    fun `delegate restart surfaces failed xray handover`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            assertTrue(delegate.tryStart(VpnRuntimeSession(), sampleResolution()))
            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))
            tunnel.failOnStart = true

            val failure =
                runCatching {
                    delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 2L)
                }.exceptionOrNull()

            assertTrue(failure is XrayProviderHandoverException)
            assertEquals("Xray provider startup failed", failure?.message)
            assertFalse(delegate.isActive)
            assertTrue(delegate.ownsActiveProviderPath)
            assertTrue(ctrl.isActive)

            tunnel.failOnStart = false
            assertTrue(delegate.tryRestart(VpnRuntimeSession(), sampleResolution(), appliedAt = 3L))
            assertTrue(delegate.isActive)
        }

    @Test
    fun `delegate restart failure retains provider ownership and tun barrier for retry`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            val session = VpnRuntimeSession()
            assertTrue(delegate.tryStart(session, sampleResolution()))
            profileStore.save("default", profile.copy(inbound = XrayProfile.LocalInbound(port = 20810)))
            tunnel.failOnStart = true

            val failure =
                runCatching {
                    delegate.tryRestart(session, sampleResolution(), appliedAt = 2L)
                }.exceptionOrNull()

            assertTrue(failure is XrayProviderHandoverException)
            assertTrue(delegate.ownsActiveProviderPath)
            assertTrue(ctrl.isActive)
            assertFalse(tunnel.isRunning)
            assertEquals(0, tunnel.stopCount)
            assertEquals(
                XrayProviderFailureClass.EngineStartFailure,
                ctrl.currentSnapshotOrNull()?.failureClass,
            )

            tunnel.failOnStart = false
            assertTrue(delegate.tryRestart(session, sampleResolution(), appliedAt = 3L))
            assertTrue(delegate.isActive)
        }

    @Test
    fun `route policy refresh refreshes policy transactionally on xray provider`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            var appliedPolicies = 0
            var publishedDnsStates = 0
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> publishedDnsStates += 1 },
                    applyActiveConnectionPolicy = { activeSession, nextResolution, _, _ ->
                        appliedPolicies += 1
                        activeSession.recordDestinationPolicy(nextResolution)
                    },
                )
            val session = VpnRuntimeSession().apply { recordDestinationPolicy(sampleResolution()) }
            assertTrue(delegate.tryStart(session, sampleResolution()))
            val bridgeStarts = bridge.startCount
            val bridgeStops = bridge.stopCount
            val tunnelStarts = tunnel.startCount
            val tunnelStops = tunnel.stopCount
            profileStore.save(
                "default",
                profile.copy(
                    name = "Osaka",
                    outbound = profile.outbound.copy(serverAddress = "198.51.100.7"),
                ),
            )

            val handled =
                delegate.tryRestart(
                    session,
                    sampleResolution().copy(destinationRoutingDigest = "replacement"),
                    appliedAt = 2L,
                    restartReason = "routing_policy_refresh",
                )

            assertTrue(handled)
            assertTrue(delegate.isActive)
            assertTrue(ctrl.isActive)
            assertTrue(bridge.startCount > bridgeStarts)
            assertEquals(bridgeStops + 1, bridge.stopCount)
            assertEquals(tunnelStarts + 1, tunnel.startCount)
            assertEquals(tunnelStops, tunnel.stopCount)
            assertEquals(1, appliedPolicies)
            assertEquals(2, publishedDnsStates)
            assertEquals("replacement", session.currentDestinationRoutingDigest)
            assertEquals("Osaka", ctrl.currentSnapshot().profileName)
            assertTrue(checkNotNull(bridge.startedConfig).contains("198.51.100.7"))
        }

    @Test
    fun `awg replacement releases xray ownership without closing the tun`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            val delegate =
                XrayConnectFlowDelegate(
                    controller = ctrl,
                    startParams = { _, _ -> params() },
                    publishActiveDnsState = { _, _ -> },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )
            val session = VpnRuntimeSession()
            assertTrue(delegate.tryStart(session, sampleResolution()))
            val previousTunnelStops = tunnel.stopCount
            val previousBridgeStops = bridge.stopCount
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Native, null))
            val awg =
                AwgActivationRequest(
                    profileId = "awg-editor",
                    privateKey = "test-private-key",
                    peerPublicKey = "test-peer-key",
                    endpointHost = "127.0.0.1",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                )

            val handled =
                delegate.tryRestart(
                    session,
                    sampleResolution(proxyPreferences = RipDpiProxyUIPreferences(awg = awg)),
                    appliedAt = 2L,
                    restartReason = "transport_failover",
                )

            assertEquals(previousTunnelStops, tunnel.stopCount)
            assertFalse(tunnel.isRunning)
            assertTrue(tunnel.quiesceCount > 0)
            assertFalse(handled)
            assertFalse(delegate.isActive)
            assertFalse(delegate.ownsActiveProviderPath)
            assertFalse(ctrl.isActive)
            assertTrue(bridge.stopCount > previousBridgeStops)
        }

    @Test
    fun `awg replacement retains cleanup ownership when xray stop fails`() =
        runTest {
            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "default"))
            profileStore.save("default", profile)
            val ctrl = controller()
            assertTrue(ctrl.start(params()) is HandoffOutcome.Running)
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw

            selectionStore.set(XrayProviderSelectionRecord.of(VpnProviderKind.Native, null))
            val failure = runCatching { ctrl.restart(params()) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(ctrl.isActive)
            assertFalse(tunnel.isRunning)
            assertEquals(0, tunnel.stopCount)
            assertTrue(startParamsHolder.current != null)
            assertTrue(ctrl.stop() is HandoffOutcome.Failed)
            assertTrue(ctrl.isActive)
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Clean
            ctrl.stop()
            assertFalse(ctrl.isActive)
            assertFalse(tunnel.isRunning)
        }
}

internal class FakeSelectionStore : XrayProviderSelectionStore {
    private var record = XrayProviderSelectionRecord()
    var onCurrent: () -> Unit = {}

    fun set(record: XrayProviderSelectionRecord) {
        this.record = record
    }

    override fun current(): XrayProviderSelectionRecord {
        onCurrent()
        return record
    }

    override fun update(record: XrayProviderSelectionRecord) {
        this.record = record
    }
}

private class XrayFailureTestDependencies(
    override val host: VpnCoordinatorHost,
    override val ioDispatcher: CoroutineDispatcher,
    override val xrayController: XrayProviderSessionController,
) : VpnTelemetryRuntimeDependencies {
    override val mutex get() = error("Unexpected non-Xray telemetry access")
    override val vpnProtectFailureMonitor get() = error("Unexpected non-Xray telemetry access")
    override val vpnTunnelRuntime get() = error("Unexpected non-Xray telemetry access")
    override val upstreamRelaySupervisor get() = error("Unexpected non-Xray telemetry access")
    override val warpRuntimeSupervisor get() = error("Unexpected non-Xray telemetry access")
    override val amneziaWgRuntimeSupervisor get() = error("Unexpected non-Xray telemetry access")
    override val proxyRuntimeSupervisor get() = error("Unexpected non-Xray telemetry access")
    override val screenStateObserver get() = error("Unexpected non-Xray telemetry access")
    override val telemetryReporter get() = error("Unexpected non-Xray telemetry access")
}

private class GenericTunnelFailureTestDependencies(
    override val host: VpnCoordinatorHost,
    override val xrayController: XrayProviderSessionController,
) : VpnTelemetryRuntimeDependencies {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val mutex get() = error("Unexpected telemetry mutex access")
    override val vpnProtectFailureMonitor get() = error("Unexpected protect monitor access")
    override val upstreamRelaySupervisor get() = error("Unexpected relay telemetry access")
    override val warpRuntimeSupervisor get() = error("Unexpected warp telemetry access")
    override val amneziaWgRuntimeSupervisor get() = error("Unexpected awg telemetry access")
    override val proxyRuntimeSupervisor get() = error("Unexpected proxy telemetry access")
    override val screenStateObserver: ScreenStateObserver = TestScreenStateObserver()
    override val vpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = host,
            appSettingsRepository = TestAppSettingsRepository(),
            proxyGroupRepository = TestProxyGroupRepository(),
            tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge()),
            vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(),
        )
    override val telemetryReporter =
        VpnRuntimeTelemetryReporter(
            host = host,
            statusReporter =
                ServiceStatusReporter(
                    mode = Mode.VPN,
                    sender = Sender.VPN,
                    serviceStateStore = TestServiceStateStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                        },
                ),
            screenStateObserver = screenStateObserver,
            directPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
            vpnTunnelRuntime = vpnTunnelRuntime,
            xrayController = xrayController,
        )
}

private class ProtectFailureTestDependencies(
    override val host: VpnCoordinatorHost,
    override val ioDispatcher: CoroutineDispatcher,
    override val xrayController: XrayProviderSessionController,
    override val vpnProtectFailureMonitor: VpnProtectFailureMonitor,
) : VpnTelemetryRuntimeDependencies {
    override val mutex get() = error("Unexpected telemetry mutex access")
    override val vpnTunnelRuntime get() = error("Unexpected tunnel telemetry access")
    override val upstreamRelaySupervisor get() = error("Unexpected relay telemetry access")
    override val warpRuntimeSupervisor get() = error("Unexpected warp telemetry access")
    override val amneziaWgRuntimeSupervisor get() = error("Unexpected awg telemetry access")
    override val proxyRuntimeSupervisor get() = error("Unexpected proxy telemetry access")
    override val screenStateObserver get() = error("Unexpected screen state access")
    override val telemetryReporter get() = error("Unexpected telemetry reporter access")
}

private class XrayFailureTestState(
    private val session: VpnRuntimeSession,
) : VpnTelemetryStateAccess {
    override fun status() = ServiceStatus.Connected

    override fun stopping() = false

    override fun runtimeSession() = session

    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? = null

    override fun currentNetworkHandoverState(): String? = null

    override fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot) = snapshot
}

private fun emptyXrayFailureTelemetry(): VpnTelemetrySnapshot =
    VpnTelemetrySnapshot(
        proxyTelemetry = NativeRuntimeSnapshot.idle("proxy"),
        proxyTelemetryStatus = RuntimeTelemetryStatus.NoData,
        relayTelemetry = NativeRuntimeSnapshot.idle("relay"),
        relayTelemetryStatus = RuntimeTelemetryStatus.NoData,
        warpTelemetry = NativeRuntimeSnapshot.idle("warp"),
        warpTelemetryStatus = RuntimeTelemetryStatus.NoData,
        awgTelemetry = NativeRuntimeSnapshot.idle("amneziawg"),
        awgTelemetryStatus = RuntimeTelemetryStatus.NoData,
        tunnelTelemetry = NativeRuntimeSnapshot.idle("tunnel"),
        tunnelTelemetryStatus = RuntimeTelemetryStatus.NoData,
    )

private fun genericTunnelFailureTelemetry(): VpnTelemetrySnapshot =
    emptyXrayFailureTelemetry().copy(
        tunnelTelemetryStatus =
            RuntimeTelemetryStatus(
                state = RuntimeTelemetryState.EngineError,
                message = "telemetry boom",
                causeClass = java.io.IOException::class.java.name,
            ),
    )

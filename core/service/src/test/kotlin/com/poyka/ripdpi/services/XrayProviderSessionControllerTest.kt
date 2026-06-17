package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.HandoffOutcome
import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.TunnelUpstream
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.testing.FakeManagedTunnel
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import kotlinx.coroutines.Dispatchers
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
class XrayProviderSessionControllerTest {
    private val bridge = FakeXrayNativeBridge()
    private val tunnel = FakeManagedTunnel()
    private val selectionStore = FakeSelectionStore()
    private val profileStore = FakeDurableXrayProfileStore()
    private val startParamsHolder = XrayTunnelStartParamsHolder()
    private val renderedConfig = arrayOfNulls<String>(1)
    private var protectDetail: String? = null

    private val profile =
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
                            publicKey = "PUBKEY_SECRET",
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                        ),
                ),
        )

    private fun controller(): XrayProviderSessionController {
        val orchestrator =
            XrayProviderOrchestrator(
                // Run the runtime's blocking native stop inline instead of on the
                // production Dispatchers.IO default; a real IO worker leaks past
                // runTest's scheduler and flakily trips UncompletedCoroutinesError on
                // the teardown path (matches XrayProviderOrchestratorTest).
                xrayRuntimeFactory = { cfg ->
                    RipDpiXrayRuntime(bridge, cfg, nativeStopDispatcher = Dispatchers.Unconfined)
                },
                tunnel = tunnel,
                protectController = { true },
                renderedConfigProvider = { checkNotNull(renderedConfig[0]) },
            )
        return XrayProviderSessionController(
            selectionStore = selectionStore,
            profileStore = profileStore,
            routeBuilder = XrayProviderRouteBuilder(profileStore),
            orchestrator = orchestrator,
            snapshotDeriver = XrayProviderSnapshotDeriver(clock = { 1L }),
            probeRunner = XrayProviderDiagnosticsProbeRunner(bridge),
            startParamsHolder = startParamsHolder,
            bridgeVersion = { bridge.version() },
            bridgeListenerReady = { bridge.listenerReady() },
            bridgeIsAlive = { bridge.isAlive() },
            renderedConfigSink = { renderedConfig[0] = it },
            lastProtectFailureDetail = { protectDetail },
        )
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
            val outcome = controller().start(params())
            assertTrue(outcome is HandoffOutcome.Failed)
            assertEquals(0, bridge.startCount)
            assertEquals(0, tunnel.startCount)
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
            assertFalse(serialized.contains("PUBKEY_SECRET"))
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
            assertFalse(ctrl.isXraySelected())
            val outcome = ctrl.start(params())
            assertEquals(HandoffOutcome.Stopped, outcome)
            assertEquals(0, bridge.startCount)
            assertEquals(0, tunnel.startCount)
        }

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

            val outcome = ctrl.restart(params())
            assertTrue(outcome is HandoffOutcome.Running)
            assertTrue(bridge.startCount > firstStarts)
            assertTrue(tunnel.startCount >= 2)
        }

    private fun params(): XrayTunnelStartParams =
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

internal class FakeSelectionStore : XrayProviderSelectionStore {
    private var record = XrayProviderSelectionRecord()

    fun set(record: XrayProviderSelectionRecord) {
        this.record = record
    }

    override suspend fun current(): XrayProviderSelectionRecord = record

    override suspend fun update(record: XrayProviderSelectionRecord) {
        this.record = record
    }
}

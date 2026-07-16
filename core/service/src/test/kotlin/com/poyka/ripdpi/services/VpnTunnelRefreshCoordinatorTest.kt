package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelRefreshCoordinatorTest {
    @Test
    fun staleRuntimeGenerationDoesNotReceiveRefreshFailure() =
        runTest {
            val initialSettings = AppSettingsSerializer.defaultValue
            val events = mutableListOf<String>()
            val originalSession = TestVpnTunnelSession(events = events)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = originalSession,
                )
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(initialSettings),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider = sessionProvider,
                )
            runtime.start(
                activeDns = initialSettings.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )
            val staleSession = VpnRuntimeSession(runtimeId = "stale")
            var activeSession: VpnRuntimeSession = staleSession
            val state = TestRefreshState { activeSession }
            val failures = mutableListOf<String>()
            val updates = mutableListOf<String>()
            val resolver =
                TestConnectionPolicyResolver(
                    sampleResolution(
                        mode = Mode.VPN,
                        settings = plainDnsSettings,
                        activeDns = plainDnsSettings.activeDnsSettings(),
                    ),
                )
            val overrides = TestResolverOverrideStore()
            val coordinator =
                VpnTunnelRefreshCoordinator(
                    dependencies =
                        object : VpnTunnelRefreshDependencies {
                            override val mutex = Mutex()
                            override val vpnTunnelRuntime = runtime
                            override val dnsPolicyCoordinator =
                                VpnDnsPolicyCoordinator(
                                    resolverRefreshPlanner =
                                        VpnResolverRefreshPlanner(
                                            connectionPolicyResolver = resolver,
                                            resolverOverrideStore = overrides,
                                        ),
                                    encryptedDnsFailoverController =
                                        VpnEncryptedDnsFailoverController(
                                            resolverOverrideStore = overrides,
                                            networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                                            networkDnsBlockedPathStore = TestNetworkDnsBlockedPathStore(),
                                            networkFingerprintProvider =
                                                TestNetworkFingerprintProvider(sampleFingerprint()),
                                            clock = TestServiceClock(),
                                        ),
                                )
                        },
                    state = state,
                    callbacks =
                        object : VpnTunnelRefreshCallbacks {
                            override fun updateRuntimeDnsState(
                                session: VpnRuntimeSession,
                                resolution: ConnectionPolicyResolution,
                            ) {
                                updates += session.runtimeId
                            }

                            override fun failTunnelRefresh(
                                session: VpnRuntimeSession,
                                error: Exception,
                            ) {
                                failures += session.runtimeId
                            }
                        },
                )
            sessionProvider.beforeEstablish = {
                activeSession = VpnRuntimeSession(runtimeId = "current")
                sessionProvider.establishFailure = IllegalStateException("stale rebuild failed")
            }

            coordinator.refreshIfNeeded(staleSession)

            assertTrue(failures.isEmpty())
            assertTrue(updates.isEmpty())
            assertEquals(ServiceStatus.Connected, state.status())
            assertFalse(originalSession.closed)
            assertTrue(runtime.isForwarding)
            sessionProvider.beforeEstablish = null
            runtime.stop()
        }

    private class TestRefreshState(
        private val sessionProvider: () -> VpnRuntimeSession?,
    ) : VpnTelemetryStateAccess {
        override fun status(): ServiceStatus = ServiceStatus.Connected

        override fun stopping(): Boolean = false

        override fun runtimeSession(): VpnRuntimeSession? = sessionProvider()

        override fun currentLocalProxyEndpoint(): LocalProxyEndpoint = localProxyEndpoint

        override fun currentNetworkHandoverState(): String? = null

        override fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot = snapshot
    }

    private companion object {
        const val TestLocalProxyAuth = "alpha-123"

        val plainDnsSettings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModePlainUdp)
                .setDnsIp("8.8.8.8")
                .build()

        val localProxyEndpoint =
            LocalProxyEndpoint(
                host = "127.0.0.1",
                port = 18080,
                username = VpnLocalProxyUsername,
                password = TestLocalProxyAuth,
            )
    }
}

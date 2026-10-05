package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider = sessionProvider,
                )
            runtime.start(
                activeDns = initialSettings.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
                configurationInput = runtime.captureConfigurationInput(),
            )
            val staleSession = VpnRuntimeSession(runtimeId = "stale")
            runtime.publishInPathLease(staleSession, localProxyEndpoint)
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
                    dependencies = buildRefreshDependencies(runtime, resolver, overrides),
                    state = state,
                    callbacks =
                        object : VpnTunnelRefreshCallbacks {
                            override fun beginDnsRefresh(
                                session: VpnRuntimeSession,
                                resolution: ConnectionPolicyResolution,
                            ) = Unit

                            override fun confirmDnsRefresh(
                                session: VpnRuntimeSession,
                                resolution: ConnectionPolicyResolution,
                            ) = Unit

                            override suspend fun recomposeRuntimeForPolicyChange(
                                session: VpnRuntimeSession,
                                resolution: ConnectionPolicyResolution,
                            ) = Unit

                            override fun updateRuntimeDnsState(
                                session: VpnRuntimeSession,
                                resolution: ConnectionPolicyResolution,
                            ) {
                                updates += session.runtimeId
                            }

                            override suspend fun failTunnelRefresh(
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

            assertEquals(null, staleSession.diagnosticsInPathRouteLease)
            assertTrue(failures.isEmpty())
            assertTrue(updates.isEmpty())
            assertEquals(ServiceStatus.Connected, state.status())
            assertFalse(originalSession.closed)
            assertTrue(runtime.isForwarding)
            sessionProvider.beforeEstablish = null
            runtime.stop()
        }

    @Test
    fun effectivePackageRouteChangesRebuildExactlyOncePerGeneration() =
        runTest {
            with(routingRefreshFixture()) {
                val initialPolicyResolutions = host.appRoutingPlanResolutions
                repeat(3) { coordinator.refreshIfNeeded(session) }
                assertEquals(initialPolicyResolutions, host.appRoutingPlanResolutions)

                groups.update(proxyGroup(listOf(initialRule), name = "Metadata only"))
                coordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true)
                assertEquals(1, events.count { it == "vpn:establish" })

                groups.update(proxyGroup(listOf(packageRule("com.example.b")), name = "Metadata only"))
                resolver.enqueue(refreshResolution(runtime, initialSettings))
                sessionProvider.session = TestVpnTunnelSession(tunFd = 8, events = events)
                listOf(
                    async { coordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true) },
                    async { coordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true) },
                ).awaitAll()
                assertEquals(1, events.count { it == "vpn:establish" })
                assertEquals(listOf(initialRule), host.lastPackageRoutingRules.toList())
                assertEquals(originalLease, session.diagnosticsInPathRouteLease)
                // Explicit transport apply consumes the newly saved rule and advances the actual lease.
                session.revokeInPathLease()
                runtime.rebuild(
                    initialSettings.activeDnsSettings(),
                    null,
                    null,
                    localProxyEndpoint,
                    configurationInput = runtime.captureConfigurationInput(),
                )
                runtime.publishInPathLease(session, localProxyEndpoint)
                assertEquals(2, events.count { it == "vpn:establish" })
                assertEquals(listOf(packageRule("com.example.b")), host.lastPackageRoutingRules.toList())
                assertEquals(listOf(null), leasesDuringEstablish)
                val rebuiltLease = requireNotNull(session.diagnosticsInPathRouteLease)
                assertEquals(receiptStore.capture().lifecycle?.generation, rebuiltLease.routeGeneration)
                assertTrue(rebuiltLease.routeGeneration > originalLease.routeGeneration)
                assertEquals(null, rebuiltLease.issuedRevision)

                coordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true)
                assertEquals(2, events.count { it == "vpn:establish" })

                groups.delete("group-1")
                resolver.enqueue(refreshResolution(runtime, initialSettings))
                sessionProvider.session = TestVpnTunnelSession(tunFd = 9, events = events)
                coordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true)

                assertEquals(2, events.count { it == "vpn:establish" })
                assertEquals(listOf(packageRule("com.example.b")), host.lastPackageRoutingRules.toList())
                session.revokeInPathLease()
                runtime.rebuild(
                    initialSettings.activeDnsSettings(),
                    null,
                    null,
                    localProxyEndpoint,
                    configurationInput = runtime.captureConfigurationInput(),
                )
                runtime.publishInPathLease(session, localProxyEndpoint)
                assertEquals(3, events.count { it == "vpn:establish" })
                assertTrue(host.lastPackageRoutingRules.isEmpty())
                assertEquals(listOf(null, null), leasesDuringEstablish)
                assertTrue(updates.isEmpty())
                runtime.stop()
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.routingRefreshFixture(): RoutingRefreshFixture {
        val initialSettings = AppSettingsSerializer.defaultValue
        val events = mutableListOf<String>()
        val initialRule = packageRule("com.example.a")
        val groups = TestProxyGroupRepository(listOf(proxyGroup(listOf(initialRule))))
        val receiptStore = VpnRouteLifecycleReceiptStore()
        val host =
            TestVpnServiceHost(backgroundScope).apply {
                appRoutingPlanResolver = { _, rules, _ ->
                    VpnAppRoutingPlan.AllowOnly(rules.mapTo(linkedSetOf()) { it.packageName })
                }
            }
        val sessionProvider =
            TestVpnTunnelSessionProvider(
                events = events,
                session = TestVpnTunnelSession(tunFd = 7, events = events),
            )
        val runtime =
            VpnTunnelRuntime(
                vpnHost = host,
                appSettingsRepository = TestAppSettingsRepository(initialSettings),
                proxyGroupRepository = groups,
                routeLifecycleReceiptStore = receiptStore,
                tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                vpnTunnelSessionProvider = sessionProvider,
            )
        runtime.start(
            activeDns = initialSettings.activeDnsSettings(),
            overrideReason = null,
            logContext = null,
            localProxyEndpoint = localProxyEndpoint,
            configurationInput = runtime.captureConfigurationInput(),
        )
        val session = VpnRuntimeSession(runtimeId = "current")
        runtime.publishInPathLease(session, localProxyEndpoint)
        val originalLease = requireNotNull(session.diagnosticsInPathRouteLease)
        val leasesDuringEstablish = mutableListOf<DiagnosticsInPathRouteLease?>()
        sessionProvider.beforeEstablish = { leasesDuringEstablish += session.diagnosticsInPathRouteLease }
        val state = TestRefreshState { session }
        val updates = mutableListOf<String>()
        val resolver =
            TestConnectionPolicyResolver(
                sampleResolution(
                    mode = Mode.VPN,
                    settings = initialSettings,
                    activeDns = initialSettings.activeDnsSettings(),
                ),
            )
        val overrides = TestResolverOverrideStore()
        val coordinator = buildRefreshCoordinator(runtime, state, resolver, overrides, updates)

        return RoutingRefreshFixture(
            initialSettings,
            events,
            initialRule,
            groups,
            receiptStore,
            host,
            sessionProvider,
            runtime,
            session,
            originalLease,
            leasesDuringEstablish,
            updates,
            resolver,
            coordinator,
        )
    }

    private class RoutingRefreshFixture(
        val initialSettings: com.poyka.ripdpi.proto.AppSettings,
        val events: MutableList<String>,
        val initialRule: com.poyka.ripdpi.data.routing.PackageRoutingRule,
        val groups: TestProxyGroupRepository,
        val receiptStore: VpnRouteLifecycleReceiptStore,
        val host: TestVpnServiceHost,
        val sessionProvider: TestVpnTunnelSessionProvider,
        val runtime: VpnTunnelRuntime,
        val session: VpnRuntimeSession,
        val originalLease: DiagnosticsInPathRouteLease,
        val leasesDuringEstablish: MutableList<DiagnosticsInPathRouteLease?>,
        val updates: MutableList<String>,
        val resolver: TestConnectionPolicyResolver,
        val coordinator: VpnTunnelRefreshCoordinator,
    )

    private fun buildRefreshDependencies(
        runtime: VpnTunnelRuntime,
        resolver: TestConnectionPolicyResolver,
        overrides: TestResolverOverrideStore,
    ): VpnTunnelRefreshDependencies =
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
        }

    private fun buildRefreshCoordinator(
        runtime: VpnTunnelRuntime,
        state: VpnTelemetryStateAccess,
        resolver: TestConnectionPolicyResolver,
        overrides: TestResolverOverrideStore,
        updates: MutableList<String>,
    ): VpnTunnelRefreshCoordinator =
        VpnTunnelRefreshCoordinator(
            dependencies = buildRefreshDependencies(runtime, resolver, overrides),
            state = state,
            callbacks =
                object : VpnTunnelRefreshCallbacks {
                    override fun beginDnsRefresh(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = Unit

                    override fun confirmDnsRefresh(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = Unit

                    override suspend fun recomposeRuntimeForPolicyChange(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = Unit

                    override fun updateRuntimeDnsState(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) {
                        updates += session.runtimeId
                    }

                    override suspend fun failTunnelRefresh(
                        session: VpnRuntimeSession,
                        error: Exception,
                    ) = throw AssertionError("Unexpected refresh failure", error)
                },
        )

    private suspend fun refreshResolution(
        runtime: VpnTunnelRuntime,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ConnectionPolicyResolution {
        val resolution =
            sampleResolution(mode = Mode.VPN, settings = settings, activeDns = settings.activeDnsSettings())
        val captured = resolution.requestedConfiguration
        return resolution.copy(
            requestedConfiguration =
                RequestedRuntimeConfiguration(
                    captured.identity,
                    captured.selection,
                    captured.dns,
                    captured.strategy,
                    runtime.captureConfigurationInput(),
                    captured.frozenTransportMaterial,
                    captured.frozenDnsMaterial,
                    captured.frozenWarpMaterial,
                    captured.warpReference,
                ),
        )
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

        fun packageRule(packageName: String): PackageRoutingRule =
            PackageRoutingRule(
                packageName = packageName,
                action = PackageRoutingAction.VIA_TUN,
                origin = PackageRoutingRuleOrigin.Subscription("sub-1"),
            )

        fun proxyGroup(
            rules: List<PackageRoutingRule>,
            name: String = "Imported",
        ): ProxyGroup =
            ProxyGroup(
                id = "group-1",
                name = name,
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
                packageRoutingRules = rules,
            )
    }
}

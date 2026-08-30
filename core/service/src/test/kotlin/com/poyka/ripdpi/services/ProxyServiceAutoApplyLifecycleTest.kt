package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.service.runtime.proxy.ProxyRuntimeSupervisorBundle
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyServiceAutoApplyLifecycleTest {
    @Test
    fun `destination policy change rebuilds active proxy runtime`() =
        runTest {
            var runtimeStarts = 0
            val routingSource = MutableDestinationRoutingPolicySource()
            val env =
                newEnv(
                    destinationRoutingPolicySource = routingSource,
                    runtimeFactory = {
                        runtimeStarts += 1
                        TestProxyRuntime()
                    },
                )

            env.coordinator.start()
            repeat(3) { runCurrent() }
            routingSource.policy = destinationRoutingPolicy()
            advanceTimeBy(1_000L)
            runCurrent()

            val session = requireNotNull(env.runtimeRegistry.current(Mode.Proxy) as? ProxyRuntimeSession)
            assertEquals(2, runtimeStarts)
            assertEquals(destinationRoutingPolicy().canonicalDigest, session.currentDestinationRoutingDigest)
            assertEquals("destination_policy_changed", session.currentActiveConnectionPolicy?.restartReason)
        }

    @Test
    fun `background automatic probing policy auto applies on matching proxy start`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val env = newEnv(fingerprint = fingerprint)
            val rememberedPolicy = automaticProbePolicy(fingerprint)
            val seeded =
                env.rememberedPolicies.rememberValidatedPolicy(
                    policy = rememberedPolicy,
                    source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                    validatedAt = env.clock.nowMillis(),
                )

            env.coordinator.start()
            repeat(3) { runCurrent() }

            val activePolicy =
                requireNotNull(
                    (env.runtimeRegistry.current(Mode.Proxy) as? ProxyRuntimeSession)?.currentActiveConnectionPolicy,
                )
            assertEquals(seeded.id, activePolicy.matchedPolicy?.id)
            assertTrue(activePolicy.usedRememberedPolicy)
            assertEquals(rememberedPolicy.proxyConfigJson, activePolicy.policy.proxyConfigJson)
        }

    @Test
    fun `failed remembered policy reuse is suppressed and later start falls back to baseline`() =
        runTest {
            val fingerprint = sampleFingerprint()
            var runtimeStarts = 0
            val env =
                newEnv(
                    fingerprint = fingerprint,
                    runtimeFactory = {
                        runtimeStarts += 1
                        TestProxyRuntime().apply {
                            if (runtimeStarts <= 2) {
                                startFailure = IOException("proxy boom")
                            }
                        }
                    },
                )
            env.rememberedPolicies.rememberValidatedPolicy(
                policy = automaticProbePolicy(fingerprint),
                source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                validatedAt = env.clock.nowMillis(),
            )

            env.coordinator.start()
            repeat(3) { runCurrent() }

            var persisted = env.recordStore.snapshot().single()
            assertEquals(RememberedNetworkPolicyStatusValidated, persisted.status)
            assertEquals(1, persisted.failureCount)
            assertEquals(1, persisted.consecutiveFailureCount)
            assertNotNull(env.rememberedPolicies.findValidatedMatch(fingerprint.scopeKey(), Mode.Proxy))

            env.coordinator.start()
            repeat(3) { runCurrent() }

            persisted = env.recordStore.snapshot().single()
            assertEquals(RememberedNetworkPolicyStatusSuppressed, persisted.status)
            assertEquals(2, persisted.failureCount)
            assertEquals(2, persisted.consecutiveFailureCount)
            assertNotNull(persisted.suppressedUntil)
            assertNull(env.rememberedPolicies.findValidatedMatch(fingerprint.scopeKey(), Mode.Proxy))

            env.coordinator.start()
            repeat(3) { runCurrent() }

            val activePolicy =
                requireNotNull(
                    (env.runtimeRegistry.current(Mode.Proxy) as? ProxyRuntimeSession)?.currentActiveConnectionPolicy,
                )
            assertNull(activePolicy.matchedPolicy)
            assertFalse(activePolicy.usedRememberedPolicy)
            assertEquals(3, runtimeStarts)
        }

    @Test
    fun `manual automatic probing entry does not auto apply on service start`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val env = newEnv(fingerprint = fingerprint)
            val manualPolicy = automaticProbePolicy(fingerprint)
            env.rememberedPolicies.upsertObservedPolicy(
                policy = manualPolicy,
                source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_MANUAL,
                observedAt = env.clock.nowMillis(),
            )

            assertNull(env.rememberedPolicies.findValidatedMatch(fingerprint.scopeKey(), Mode.Proxy))

            env.coordinator.start()
            repeat(3) { runCurrent() }

            val activePolicy =
                requireNotNull(
                    (env.runtimeRegistry.current(Mode.Proxy) as? ProxyRuntimeSession)?.currentActiveConnectionPolicy,
                )
            assertNull(activePolicy.matchedPolicy)
            assertFalse(activePolicy.usedRememberedPolicy)
            assertEquals(manualPolicy.fingerprintHash, activePolicy.fingerprintHash)
        }

    @Suppress("LongMethod")
    private fun TestScope.newEnv(
        fingerprint: com.poyka.ripdpi.data.NetworkFingerprint = sampleFingerprint(),
        runtimeFactory: () -> TestProxyRuntime = { TestProxyRuntime() },
        destinationRoutingPolicySource: DestinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
    ): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestServiceClock(now = 1_000L)
        val recordStore = InMemoryRememberedNetworkPolicyRecordStore(nowProvider = clock::nowMillis)
        val rememberedPolicies =
            DefaultRememberedNetworkPolicyStore(
                recordStore = recordStore,
                clock = DiagnosticsHistoryClock { clock.nowMillis() },
            )
        val appSettings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setEnableCmdSettings(false)
                .setNetworkStrategyMemoryEnabled(true)
                .build()
        val appSettingsRepository = TestAppSettingsRepository(appSettings)
        val fingerprintProvider = TestNetworkFingerprintProvider(fingerprint)
        val host = TestProxyServiceHost(backgroundScope)
        val stateStore = TestServiceStateStore()
        val factory = TestRipDpiProxyFactory(runtimeFactory)
        val warpFactory = TestRipDpiWarpFactory { TestWarpRuntime() }
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val resolver =
            DefaultConnectionPolicyResolver(
                context = RuntimeEnvironment.getApplication(),
                appSettingsRepository = appSettingsRepository,
                networkFingerprintProvider = fingerprintProvider,
                networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                antiCorrelationRoutingPolicy =
                    object : AntiCorrelationRoutingPolicy {
                        override fun apply(
                            settings: com.poyka.ripdpi.proto.AppSettings,
                            preferredEdges: Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>>,
                        ): Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>> = preferredEdges
                    },
                rememberedNetworkPolicyStore = rememberedPolicies,
                rootHelperManager = RootHelperManager(),
                environmentDetector = EnvironmentDetector(),
                serverCapabilityStore = TestServerCapabilityStore(),
                awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                destinationRoutingPolicySource = destinationRoutingPolicySource,
                proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
            )
        val coordinator =
            ProxyServiceRuntimeCoordinator(
                host = host,
                connectionPolicyResolver = resolver,
                serviceRuntimeRegistry = runtimeRegistry,
                rememberedNetworkPolicyStore = rememberedPolicies,
                networkHandoverMonitor = TestNetworkHandoverMonitor(),
                policyHandoverEventStore = TestPolicyHandoverEventStore(),
                permissionWatchdog = TestPermissionWatchdog(),
                supervisors =
                    ProxyRuntimeSupervisorBundle(
                        upstreamRelaySupervisor =
                            UpstreamRelaySupervisor(
                                scope = backgroundScope,
                                dispatcher = dispatcher,
                                relayFactory = TestRipDpiRelayFactory(),
                                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                                relayProfileStore = TestRelayProfileStore(),
                                relayCredentialStore = TestRelayCredentialStore(),
                            ),
                        warpRuntimeSupervisor =
                            WarpRuntimeSupervisor(
                                scope = backgroundScope,
                                dispatcher = dispatcher,
                                warpFactory = warpFactory,
                                runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                            ),
                        amneziaWgRuntimeSupervisor =
                            AmneziaWgRuntimeSupervisor(
                                scope = backgroundScope,
                                dispatcher = dispatcher,
                                amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                                runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                            ),
                        proxyRuntimeSupervisor =
                            ProxyRuntimeSupervisor(
                                scope = backgroundScope,
                                dispatcher = dispatcher,
                                ripDpiProxyFactory = factory,
                                networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                            ),
                    ),
                autolearnActivationReceiptPublisher = testAutolearnActivationReceiptPublisher(),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.Proxy,
                        sender = Sender.Proxy,
                        serviceStateStore = stateStore,
                        networkFingerprintProvider = fingerprintProvider,
                        telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                        runtimeExperimentSelectionProvider =
                            object : RuntimeExperimentSelectionProvider {
                                override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                            },
                        clock = clock,
                    ),
                screenStateObserver = TestScreenStateObserver(),
                ioDispatcher = dispatcher,
                clock = clock,
            )
        return Env(
            coordinator = coordinator,
            rememberedPolicies = rememberedPolicies,
            recordStore = recordStore,
            runtimeRegistry = runtimeRegistry,
            store = stateStore,
            factory = factory,
            clock = clock,
        )
    }

    private fun automaticProbePolicy(
        fingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
        mode: Mode = Mode.Proxy,
    ): RememberedNetworkPolicyJson =
        RememberedNetworkPolicyJson(
            fingerprintHash = fingerprint.scopeKey(),
            mode = mode.preferenceValue,
            summary = fingerprint.summary(),
            proxyConfigJson = RipDpiProxyUIPreferences().toNativeConfigJson(),
            winningTcpStrategyFamily = "tcp-family",
        )

    private fun destinationRoutingPolicy(): DestinationRoutingPolicy =
        DestinationRoutingPolicy(
            rules =
                listOf(
                    DestinationRoutingRule(
                        action = DestinationRoutingAction.DIRECT,
                        network = DestinationRoutingNetwork.BOTH,
                        domains =
                            listOf(
                                DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "example.com"),
                                DestinationDomainMatcher(DestinationDomainMatcherKind.SUFFIX, "example.net"),
                                DestinationDomainMatcher(DestinationDomainMatcherKind.GEOSITE, "ru"),
                            ),
                        ipRanges =
                            listOf(
                                DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24"),
                                DestinationIpMatcher(DestinationIpMatcherKind.GEO_IP, "ru"),
                            ),
                        destinationPorts = listOf(DestinationPortRange(443, 8443)),
                    ),
                ),
            canonicalDigest = "e7ed9f9ec8688b89eea6f22a7ae6e93e7f441a903b9f9de96d387700c122bee7",
        )

    private data class Env(
        val coordinator: ProxyServiceRuntimeCoordinator,
        val rememberedPolicies: DefaultRememberedNetworkPolicyStore,
        val recordStore: InMemoryRememberedNetworkPolicyRecordStore,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val store: TestServiceStateStore,
        val factory: TestRipDpiProxyFactory,
        val clock: TestServiceClock,
    )
}

private class MutableDestinationRoutingPolicySource : DestinationRoutingPolicySource {
    var policy: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = "")

    override suspend fun snapshot(): DestinationRoutingPolicySnapshot =
        DestinationRoutingPolicySnapshot.Available(policy)
}

private class InMemoryRememberedNetworkPolicyRecordStore(
    private val nowProvider: () -> Long,
) : RememberedNetworkPolicyRecordStore {
    private val state = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())
    private var nextId = 1L

    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
        state.asStateFlow().map { policies -> policies.take(limit) }

    override suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        state.value.firstOrNull { it.fingerprintHash == fingerprintHash && it.mode == mode }

    override suspend fun getRememberedNetworkPolicyById(id: Long): RememberedNetworkPolicyEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        state.value
            .asSequence()
            .filter { it.fingerprintHash == fingerprintHash && it.mode == mode }
            .filter { it.status == RememberedNetworkPolicyStatusValidated }
            .filter { policy ->
                val suppressedUntil = policy.suppressedUntil
                suppressedUntil == null || suppressedUntil <= nowProvider()
            }.sortedWith(
                compareByDescending<RememberedNetworkPolicyEntity> { it.lastValidatedAt ?: 0L }
                    .thenByDescending { it.updatedAt },
            ).firstOrNull()

    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long {
        val assignedId =
            if (policy.id > 0L) {
                policy.id
            } else {
                nextId++
            }
        val persisted = policy.copy(id = assignedId)
        state.value =
            state.value
                .filterNot { it.id == assignedId }
                .plus(persisted)
        return assignedId
    }

    override suspend fun clearRememberedNetworkPolicies() {
        state.value = emptyList()
    }

    override suspend fun deleteRememberedNetworkPolicy(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun countRememberedNetworkPoliciesForFingerprint(fingerprintHash: String): Int =
        state.value.count { it.fingerprintHash == fingerprintHash }

    override suspend fun pruneRememberedNetworkPolicies() = Unit

    fun snapshot(): List<RememberedNetworkPolicyEntity> = state.value.sortedBy { it.id }
}

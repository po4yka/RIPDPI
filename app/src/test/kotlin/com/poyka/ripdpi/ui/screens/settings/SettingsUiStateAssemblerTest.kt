package com.poyka.ripdpi.ui.screens.settings

import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WarpPayloadGenCatalog
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.security.BiometricCapabilityChecker
import com.poyka.ripdpi.services.EnginePlatformCapabilities
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsUiStateAssemblerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `assembler carries remembered network count and warp suggestion into ui state`() =
        runTest {
            val rememberedPolicies =
                MutableStateFlow(
                    listOf(
                        rememberedPolicy("fp-1"),
                        rememberedPolicy("fp-2"),
                    ),
                )
            val hostAutolearnStoreController = FakeHostAutolearnStoreController(hasStore = true)
            val settingsUiDependencies =
                SettingsUiDependencies(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    rememberedPolicySource = FlowRememberedPolicySource(rememberedPolicies),
                    serviceStateStore = FakeServiceStateStore(),
                    hostAutolearnStoreController = hostAutolearnStoreController,
                    routingProtectionCatalogService = SnapshotRoutingProtectionCatalogService(),
                    warpPayloadGenCatalog =
                        WarpPayloadGenCatalog(
                            ApplicationProvider.getApplicationContext(),
                        ),
                    networkSnapshotProvider =
                        FixedNetworkSnapshotProvider(
                            NativeNetworkSnapshot(
                                transport = "cellular",
                                cellular =
                                    NativeCellularSnapshot(
                                        generation = "4g",
                                        operatorCode = "25001",
                                    ),
                            ),
                        ),
                    enginePlatformCapabilities = FakeEnginePlatformCapabilities(),
                )
            val assembler =
                SettingsUiStateAssembler(
                    FakeBiometricCapabilityChecker(),
                    testDispatchers(testScheduler),
                    TestNetworkHandoverMonitor(),
                )
            val assemblySnapshot =
                assembler.buildAssemblySnapshot(
                    settings = settingsUiDependencies.appSettingsRepository.snapshot(),
                    serviceTelemetry = settingsUiDependencies.serviceStateStore.telemetry.value,
                    rememberedNetworkCount = rememberedPolicies.value.size,
                    settingsUiDependencies = settingsUiDependencies,
                )
            val uiState = assembler.buildUiState(assemblySnapshot)

            assertEquals(2, uiState.autolearn.rememberedNetworkCount)
            assertTrue(uiState.autolearn.hostAutolearnStorePresent)
            assertEquals("quic_imitation", uiState.warp.amneziaSuggestedPresetId)
            assertEquals("QUIC imitation", uiState.warp.amneziaSuggestedPresetLabel)
        }

    @Test
    fun `assembler refreshes network hints without settings changes`() =
        runTest {
            val initialSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWarpEnabled(true)
                    .build()
            val settingsRepository = FakeAppSettingsRepository(initialSettings)
            val routingCatalog = CountingRoutingProtectionCatalogService()
            val snapshotProvider =
                FixedNetworkSnapshotProvider(
                    NativeNetworkSnapshot(
                        transport = "cellular",
                        privateDnsMode = "system",
                        cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                    ),
                )
            val monitor = TestNetworkHandoverMonitor()
            val dependencies =
                networkTestDependencies(snapshotProvider, settingsRepository, routingCatalog = routingCatalog)
            val state =
                SettingsUiStateAssembler(FakeBiometricCapabilityChecker(), testDispatchers(testScheduler), monitor)
                    .assemble(backgroundScope, dependencies, MutableStateFlow(0))
            val collector = launch { state.collect {} }
            runCurrent()
            assertEquals("quic_imitation", state.value.warp.amneziaSuggestedPresetId)
            assertEquals(SystemPrivateDnsStatus.ABSENT.wireValue, state.value.dns.systemPrivateDnsStatus)

            snapshotProvider.snapshot =
                NativeNetworkSnapshot(
                    transport = "cellular",
                    privateDnsMode = "dns.example",
                    cellular = NativeCellularSnapshot(operatorCode = "310260", generation = "5g"),
                )
            monitor.emitChange(snapshotProvider.snapshot)
            runCurrent()
            assertEquals("tls_imitation", state.value.warp.amneziaSuggestedPresetId)
            assertEquals(SystemPrivateDnsStatus.PRESENT.wireValue, state.value.dns.systemPrivateDnsStatus)

            snapshotProvider.snapshot = NativeNetworkSnapshot(transport = "wifi", privateDnsMode = "system")
            monitor.emitChange(snapshotProvider.snapshot)
            runCurrent()
            assertEquals("", state.value.warp.amneziaSuggestedPresetId)
            assertEquals(SystemPrivateDnsStatus.ABSENT.wireValue, state.value.dns.systemPrivateDnsStatus)
            assertEquals("off", state.value.warp.amneziaPreset)
            assertEquals(initialSettings, settingsRepository.snapshot())
            assertEquals(1, routingCatalog.snapshotCalls)
            assertEquals(1, monitor.events.subscriptionCount.value)

            collector.cancel()
            advanceTimeBy(6_000L)
            runCurrent()
            assertEquals(0, monitor.events.subscriptionCount.value)
        }

    @Test
    fun `initial hint captures the network after observation starts`() =
        runTest {
            val snapshotProvider =
                FixedNetworkSnapshotProvider(
                    NativeNetworkSnapshot(
                        transport = "cellular",
                        cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                    ),
                )
            val monitor =
                TestNetworkHandoverMonitor(
                    onObservationStarted = {
                        snapshotProvider.snapshot =
                            NativeNetworkSnapshot(
                                transport = "cellular",
                                cellular = NativeCellularSnapshot(operatorCode = "310260", generation = "5g"),
                            )
                    },
                )
            val state =
                SettingsUiStateAssembler(FakeBiometricCapabilityChecker(), testDispatchers(testScheduler), monitor)
                    .assemble(backgroundScope, networkTestDependencies(snapshotProvider), MutableStateFlow(0))
            val collector = launch { state.collect {} }
            runCurrent()
            assertEquals("tls_imitation", state.value.warp.amneziaSuggestedPresetId)
            collector.cancel()
        }

    @Test
    fun `empty network observation still captures initial hints`() =
        runTest {
            val provider =
                FixedNetworkSnapshotProvider(
                    NativeNetworkSnapshot(
                        transport = "cellular",
                        cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                    ),
                )
            val assembler =
                SettingsUiStateAssembler(
                    FakeBiometricCapabilityChecker(),
                    testDispatchers(testScheduler),
                    TestNetworkHandoverMonitor(observeChanges = false),
                )
            val state = assembler.assemble(backgroundScope, networkTestDependencies(provider), MutableStateFlow(0))
            val collector = launch { state.collect {} }
            runCurrent()
            assertEquals("quic_imitation", state.value.warp.amneziaSuggestedPresetId)
            collector.cancel()
        }

    @Test
    fun `cancelled network observation does not publish an initial hint or fallback`() =
        runTest {
            val provider =
                FixedNetworkSnapshotProvider(
                    NativeNetworkSnapshot(
                        transport = "cellular",
                        cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                    ),
                )
            val assembler =
                SettingsUiStateAssembler(
                    FakeBiometricCapabilityChecker(),
                    testDispatchers(testScheduler),
                    TestNetworkHandoverMonitor(CancellationException("Observation cancelled")),
                )
            val state = assembler.assemble(backgroundScope, networkTestDependencies(provider), MutableStateFlow(0))
            val collector = launch { state.collect {} }
            runCurrent()
            assertFalse(state.value.isHydrated)
            assertEquals("", state.value.warp.amneziaSuggestedPresetId)
            collector.cancel()
        }

    @Test
    fun `network observation failure does not stop settings telemetry`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val dependencies =
                networkTestDependencies(
                    FixedNetworkSnapshotProvider(
                        NativeNetworkSnapshot(
                            transport = "cellular",
                            privateDnsMode = "dns.example",
                            cellular = NativeCellularSnapshot(operatorCode = "25001", generation = "4g"),
                        ),
                    ),
                    serviceStateStore = serviceStateStore,
                )
            val assembler =
                SettingsUiStateAssembler(
                    FakeBiometricCapabilityChecker(),
                    testDispatchers(testScheduler),
                    TestNetworkHandoverMonitor(IllegalStateException("Network observation is unavailable")),
                )
            val state = assembler.assemble(backgroundScope, dependencies, MutableStateFlow(0))
            val collector = launch { state.collect {} }
            runCurrent()
            assertTrue(state.value.isHydrated)
            assertEquals("", state.value.warp.amneziaSuggestedPresetId)
            assertEquals(SystemPrivateDnsStatus.UNKNOWN.wireValue, state.value.dns.systemPrivateDnsStatus)

            serviceStateStore.updateTelemetry(ServiceTelemetrySnapshot(mode = Mode.VPN, status = AppStatus.Running))
            runCurrent()
            assertEquals(AppStatus.Running, state.value.serviceStatus)
            collector.cancel()
        }

    @Test
    fun `assembler does not refresh routing snapshot for telemetry-only emissions`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val routingProtectionCatalogService = CountingRoutingProtectionCatalogService()
            val settingsUiDependencies =
                SettingsUiDependencies(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    rememberedPolicySource =
                        FlowRememberedPolicySource(
                            MutableStateFlow(emptyList<DiagnosticsRememberedPolicy>()),
                        ),
                    serviceStateStore = serviceStateStore,
                    hostAutolearnStoreController = FakeHostAutolearnStoreController(hasStore = true),
                    routingProtectionCatalogService = routingProtectionCatalogService,
                    warpPayloadGenCatalog =
                        WarpPayloadGenCatalog(
                            ApplicationProvider.getApplicationContext(),
                        ),
                    networkSnapshotProvider =
                        FixedNetworkSnapshotProvider(
                            NativeNetworkSnapshot(transport = "wifi"),
                        ),
                    enginePlatformCapabilities = FakeEnginePlatformCapabilities(),
                )
            val assembler =
                SettingsUiStateAssembler(
                    FakeBiometricCapabilityChecker(),
                    testDispatchers(testScheduler),
                    TestNetworkHandoverMonitor(),
                )
            val uiState =
                assembler.assemble(
                    scope = backgroundScope,
                    settingsUiDependencies = settingsUiDependencies,
                    hostAutolearnStoreRefresh = MutableStateFlow(0),
                )

            assertFalse(uiState.value.isHydrated)
            assertEquals(0, routingProtectionCatalogService.snapshotCalls)

            val collector = launch { uiState.collect {} }
            runCurrent()
            val callsAfterInitialAssembly = routingProtectionCatalogService.snapshotCalls

            assertTrue(uiState.value.isHydrated)
            assertEquals(1, callsAfterInitialAssembly)

            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    updatedAt = 1L,
                ),
            )
            runCurrent()
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Halted,
                    updatedAt = 2L,
                ),
            )
            runCurrent()

            assertEquals(callsAfterInitialAssembly, routingProtectionCatalogService.snapshotCalls)
            collector.cancel()
        }
}

private fun testDispatchers(testScheduler: TestCoroutineScheduler): AppCoroutineDispatchers {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return AppCoroutineDispatchers(
        default = dispatcher,
        io = dispatcher,
        main = dispatcher,
    )
}

private class FlowRememberedPolicySource(
    private val policies: MutableStateFlow<List<DiagnosticsRememberedPolicy>>,
) : DiagnosticsRememberedPolicySource {
    override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = policies

    override suspend fun deletePolicy(policy: DiagnosticsRememberedPolicy) {
        policies.value = policies.value.filterNot { it.id == policy.id }
    }

    override suspend fun clearAll() {
        policies.value = emptyList()
    }
}

private class FakeHostAutolearnStoreController(
    private val hasStore: Boolean,
) : HostAutolearnStoreController {
    override fun hasStore(): Boolean = hasStore

    override fun clearStore(): Boolean = true

    override fun markErasePending(): Boolean = true

    override fun applyPendingErase(): Boolean = true
}

private class FakeEnginePlatformCapabilities : EnginePlatformCapabilities {
    override fun seqovlSupported(): Boolean = true
}

private class FakeBiometricCapabilityChecker : BiometricCapabilityChecker {
    override fun canAuthenticate(): Int = BiometricManager.BIOMETRIC_SUCCESS
}

private class SnapshotRoutingProtectionCatalogService : RoutingProtectionCatalogService {
    override fun snapshot(): RoutingProtectionCatalogSnapshot = RoutingProtectionCatalogSnapshot()
}

private class CountingRoutingProtectionCatalogService : RoutingProtectionCatalogService {
    var snapshotCalls = 0
        private set

    override fun snapshot(): RoutingProtectionCatalogSnapshot {
        snapshotCalls += 1
        return RoutingProtectionCatalogSnapshot()
    }
}

private class FixedNetworkSnapshotProvider(
    var snapshot: NativeNetworkSnapshot,
) : NativeNetworkSnapshotProvider {
    override fun capture(): NativeNetworkSnapshot = snapshot
}

private fun rememberedPolicy(fingerprintHash: String): DiagnosticsRememberedPolicy =
    DiagnosticsRememberedPolicy(
        fingerprintHash = fingerprintHash,
        mode = "vpn",
        proxyConfigJson = "{}",
        source = RememberedNetworkPolicySource.MANUAL_SESSION,
        status = "active",
        firstObservedAt = 1L,
        updatedAt = 1L,
    )

private class TestNetworkHandoverMonitor(
    private val observationFailure: Exception? = null,
    private val onObservationStarted: () -> Unit = {},
    private val observeChanges: Boolean = true,
) : NetworkHandoverMonitor {
    override val events = MutableSharedFlow<NetworkHandoverEvent>()

    override fun observe(): Flow<NetworkHandoverEvent> =
        flow {
            onObservationStarted()
            observationFailure?.let { throw it }
            if (observeChanges) emitAll(events)
        }

    suspend fun emitChange(snapshot: NativeNetworkSnapshot) {
        events.emit(
            NetworkHandoverEvent(
                previousFingerprint = null,
                currentFingerprint =
                    NetworkFingerprint(
                        transport = snapshot.transport,
                        networkValidated = snapshot.validated,
                        captivePortalDetected = snapshot.captivePortal,
                        privateDnsMode = snapshot.privateDnsMode,
                        dnsServers = snapshot.dnsServers,
                    ),
                classification = "link_refresh",
                occurredAt = 1L,
            ),
        )
    }
}

private fun networkTestDependencies(
    snapshotProvider: NativeNetworkSnapshotProvider,
    settingsRepository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
    serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
    routingCatalog: RoutingProtectionCatalogService = SnapshotRoutingProtectionCatalogService(),
): SettingsUiDependencies =
    SettingsUiDependencies(
        appSettingsRepository = settingsRepository,
        rememberedPolicySource = FlowRememberedPolicySource(MutableStateFlow(emptyList())),
        serviceStateStore = serviceStateStore,
        hostAutolearnStoreController = FakeHostAutolearnStoreController(hasStore = true),
        routingProtectionCatalogService = routingCatalog,
        warpPayloadGenCatalog = WarpPayloadGenCatalog(ApplicationProvider.getApplicationContext()),
        networkSnapshotProvider = snapshotProvider,
        enginePlatformCapabilities = FakeEnginePlatformCapabilities(),
    )

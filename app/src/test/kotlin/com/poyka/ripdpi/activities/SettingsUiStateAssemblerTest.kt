package com.poyka.ripdpi.activities

import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WarpPayloadGenCatalog
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.security.BiometricCapabilityChecker
import com.poyka.ripdpi.services.EnginePlatformCapabilities
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            val assembler = SettingsUiStateAssembler(FakeBiometricCapabilityChecker())
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
            val assembler = SettingsUiStateAssembler(FakeBiometricCapabilityChecker())
            val uiState =
                assembler.assemble(
                    scope = backgroundScope,
                    settingsUiDependencies = settingsUiDependencies,
                    hostAutolearnStoreRefresh = MutableStateFlow(0),
                )
            val collector = backgroundScope.launch { uiState.collect {} }
            advanceUntilIdle()
            val callsAfterInitialAssembly = routingProtectionCatalogService.snapshotCalls

            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    updatedAt = 1L,
                ),
            )
            advanceUntilIdle()
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Halted,
                    updatedAt = 2L,
                ),
            )
            advanceUntilIdle()

            assertEquals(callsAfterInitialAssembly, routingProtectionCatalogService.snapshotCalls)
            collector.cancel()
        }
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
    private val snapshot: NativeNetworkSnapshot,
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

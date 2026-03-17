package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.services.DefaultPolicyHandoverEventStore
import com.poyka.ripdpi.services.DefaultResolverOverrideStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TestAutomaticHandoverProbeDelayMs = 15_000L
private const val TestAutomaticHandoverProbeCooldownMs = 24L * 60L * 60L * 1_000L

internal fun createDiagnosticsManager(
    context: Context,
    appSettingsRepository: AppSettingsRepository,
    historyRepository: DiagnosticsHistoryRepository,
    networkMetadataProvider: NetworkMetadataProvider,
    diagnosticsContextProvider: DiagnosticsContextProvider,
    networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
    runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    serviceStateStore: ServiceStateStore,
    logcatSnapshotCollector: LogcatSnapshotCollector = LogcatSnapshotCollector(),
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(historyRepository),
    networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore =
        DefaultNetworkDnsPathPreferenceStore(historyRepository),
    networkFingerprintProvider: NetworkFingerprintProvider =
        object : NetworkFingerprintProvider {
            override fun capture() = null
        },
    nativeNetworkSnapshotProvider: NativeNetworkSnapshotProvider =
        object : NativeNetworkSnapshotProvider {
            override fun capture() = NativeNetworkSnapshot()
        },
    resolverOverrideStore: ResolverOverrideStore = DefaultResolverOverrideStore(),
    policyHandoverEventStore: PolicyHandoverEventStore = DefaultPolicyHandoverEventStore(),
    automaticHandoverProbeDelayMs: Long = TestAutomaticHandoverProbeDelayMs,
    automaticHandoverProbeCooldownMs: Long = TestAutomaticHandoverProbeCooldownMs,
    importBundledProfilesOnInitialize: Boolean = true,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
): DefaultDiagnosticsManager =
    DefaultDiagnosticsManager(
        context = context,
        appSettingsRepository = appSettingsRepository,
        historyRepository = historyRepository,
        logcatSnapshotCollector = logcatSnapshotCollector,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
        networkMetadataProvider = networkMetadataProvider,
        networkFingerprintProvider = networkFingerprintProvider,
        nativeNetworkSnapshotProvider = nativeNetworkSnapshotProvider,
        diagnosticsContextProvider = diagnosticsContextProvider,
        networkDiagnosticsBridgeFactory = networkDiagnosticsBridgeFactory,
        runtimeCoordinator = runtimeCoordinator,
        serviceStateStore = serviceStateStore,
        resolverOverrideStore = resolverOverrideStore,
        policyHandoverEventStore = policyHandoverEventStore,
        automaticHandoverProbeDelayMs = automaticHandoverProbeDelayMs,
        automaticHandoverProbeCooldownMs = automaticHandoverProbeCooldownMs,
        importBundledProfilesOnInitialize = importBundledProfilesOnInitialize,
        scope = scope,
    )

internal fun createRuntimeHistoryRecorder(
    appSettingsRepository: AppSettingsRepository,
    historyRepository: DiagnosticsHistoryRepository,
    networkMetadataProvider: NetworkMetadataProvider,
    diagnosticsContextProvider: DiagnosticsContextProvider,
    serviceStateStore: ServiceStateStore,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(historyRepository),
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
): DefaultRuntimeHistoryRecorder =
    DefaultRuntimeHistoryRecorder(
        appSettingsRepository = appSettingsRepository,
        historyRepository = historyRepository,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkMetadataProvider = networkMetadataProvider,
        diagnosticsContextProvider = diagnosticsContextProvider,
        serviceStateStore = serviceStateStore,
        activeConnectionPolicyStore = activeConnectionPolicyStore,
        scope = scope,
    )

private class EmptyActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
    override val activePolicies: StateFlow<Map<com.poyka.ripdpi.data.Mode, ActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}

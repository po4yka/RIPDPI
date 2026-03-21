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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import javax.inject.Provider

private const val TestAutomaticHandoverProbeDelayMs = 15_000L
private const val TestAutomaticHandoverProbeCooldownMs = 24L * 60L * 60L * 1_000L

internal data class DiagnosticsServicesBundle(
    val bootstrapper: DiagnosticsBootstrapper,
    val timelineSource: DefaultDiagnosticsTimelineSource,
    val scanController: DefaultDiagnosticsScanController,
    val detailLoader: DiagnosticsDetailLoader,
    val shareService: DiagnosticsShareService,
    val resolverActions: DiagnosticsResolverActions,
)

internal fun createDiagnosticsServices(
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
    resolverOverrideStore: ResolverOverrideStore = FakeResolverOverrideStore(),
    policyHandoverEventStore: PolicyHandoverEventStore = FakePolicyHandoverEventStore(),
    automaticHandoverProbeDelayMs: Long = TestAutomaticHandoverProbeDelayMs,
    automaticHandoverProbeCooldownMs: Long = TestAutomaticHandoverProbeCooldownMs,
    importBundledProfilesOnInitialize: Boolean = false,
    json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        },
    archiveExporter: DiagnosticsArchiveExporter =
        DefaultDiagnosticsArchiveExporter(
            historyRepository = historyRepository,
            sourceLoader =
                DiagnosticsArchiveSourceLoader(
                    historyRepository = historyRepository,
                    logcatSnapshotCollector = logcatSnapshotCollector,
                    json = json,
                ),
            sessionSelector =
                DiagnosticsArchiveSessionSelector(
                    redactor = DiagnosticsArchiveRedactor(json),
                    json = json,
                ),
            renderer =
                DiagnosticsArchiveRenderer(
                    redactor = DiagnosticsArchiveRedactor(json),
                    json = json,
                ),
            fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = context.cacheDir,
                    clock = DiagnosticsArchiveClock { System.currentTimeMillis() },
                ),
            zipWriter = DiagnosticsArchiveZipWriter(),
            idGenerator = DiagnosticsArchiveIdGenerator { java.util.UUID.randomUUID().toString() },
        ),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
): DiagnosticsServicesBundle {
    lateinit var scanController: DefaultDiagnosticsScanController
    val timelineSource = DefaultDiagnosticsTimelineSource(historyRepository, json)
    val requestFactory =
        DiagnosticsScanRequestFactory(
            context = context,
            networkMetadataProvider = networkMetadataProvider,
            networkFingerprintProvider = networkFingerprintProvider,
            nativeNetworkSnapshotProvider = nativeNetworkSnapshotProvider,
            diagnosticsContextProvider = diagnosticsContextProvider,
            networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
            serviceStateStore = serviceStateStore,
            json = json,
        )
    val executionCoordinator =
        DiagnosticsScanExecutionCoordinator(
            context = context,
            historyRepository = historyRepository,
            networkMetadataProvider = networkMetadataProvider,
            networkFingerprintProvider = networkFingerprintProvider,
            diagnosticsContextProvider = diagnosticsContextProvider,
            serviceStateStore = serviceStateStore,
            resolverOverrideStore = resolverOverrideStore,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
            json = json,
        )
    val scheduler =
        AutomaticProbeScheduler(
            appSettingsRepository = appSettingsRepository,
            launcherProvider =
                object : Provider<AutomaticProbeLauncher> {
                    override fun get(): AutomaticProbeLauncher = scanController
                },
            automaticHandoverProbeDelayMs = automaticHandoverProbeDelayMs,
            automaticHandoverProbeCooldownMs = automaticHandoverProbeCooldownMs,
            scope = scope,
        )
    val recommendationStore = DiagnosticsRecommendationStore(historyRepository, json)
    scanController =
        DefaultDiagnosticsScanController(
            appSettingsRepository = appSettingsRepository,
            historyRepository = historyRepository,
            networkDiagnosticsBridgeFactory = networkDiagnosticsBridgeFactory,
            runtimeCoordinator = runtimeCoordinator,
            scanRequestFactory = requestFactory,
            executionCoordinator = executionCoordinator,
            timelineSource = timelineSource,
            scope = scope,
        )
    return DiagnosticsServicesBundle(
        bootstrapper =
            DefaultDiagnosticsBootstrapper(
                archiveExporter = archiveExporter,
                profileImporter =
                    BundledDiagnosticsProfileImporter(
                        profileSource = AssetBundledDiagnosticsProfileSource(context),
                        historyRepository = historyRepository,
                        json = json,
                    ),
                policyHandoverEventStore = policyHandoverEventStore,
                automaticProbeScheduler = scheduler,
                importBundledProfilesOnInitialize = importBundledProfilesOnInitialize,
                scope = scope,
            ),
        timelineSource = timelineSource,
        scanController = scanController,
        detailLoader = DefaultDiagnosticsDetailLoader(historyRepository, json),
        shareService = DefaultDiagnosticsShareService(historyRepository, archiveExporter, json),
        resolverActions =
            DefaultDiagnosticsResolverActions(
                appSettingsRepository = appSettingsRepository,
                recommendationStore = recommendationStore,
                networkFingerprintProvider = networkFingerprintProvider,
                networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
                resolverOverrideStore = resolverOverrideStore,
            ),
    )
}

internal fun createRuntimeHistoryRecorder(
    appSettingsRepository: AppSettingsRepository,
    historyRepository: DiagnosticsHistoryRepository,
    networkMetadataProvider: NetworkMetadataProvider,
    diagnosticsContextProvider: DiagnosticsContextProvider,
    serviceStateStore: ServiceStateStore,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(historyRepository),
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
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

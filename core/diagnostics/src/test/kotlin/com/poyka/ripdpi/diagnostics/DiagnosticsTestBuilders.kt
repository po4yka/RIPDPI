package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultDeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NoopStartupJournal
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementBarrier
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import javax.inject.Provider
import kotlin.coroutines.ContinuationInterceptor

private const val TestAutomaticHandoverProbeDelayMs = 15_000L
private const val TestAutomaticHandoverProbeCooldownMs = 24L * 60L * 60L * 1_000L

private class FakeDiagnosticsHomeCompositeRunService : DiagnosticsHomeCompositeRunService {
    override suspend fun startHomeAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted =
        error("unused")

    override suspend fun startQuickAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted =
        error("unused")

    override fun observeHomeRun(runId: String): StateFlow<DiagnosticsHomeCompositeProgress> =
        MutableStateFlow(
            DiagnosticsHomeCompositeProgress(
                runId = runId,
                stages = emptyList(),
            ),
        )

    override suspend fun cancelHomeRun(runId: String) = Unit

    override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome = error("unused")

    override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = null

    override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit
}

internal data class DiagnosticsServicesBundle(
    val bootstrapper: DiagnosticsBootstrapper,
    val timelineSource: DefaultDiagnosticsTimelineSource,
    val scanController: DefaultDiagnosticsScanController,
    val detailLoader: DiagnosticsDetailLoader,
    val shareService: DiagnosticsShareService,
    val resolverActions: DiagnosticsResolverActions,
)

@Suppress("LongParameterList", "LongMethod")
internal fun createDiagnosticsServices(
    context: Context,
    appSettingsRepository: AppSettingsRepository,
    stores: FakeDiagnosticsHistoryStores,
    networkMetadataProvider: NetworkMetadataProvider,
    diagnosticsContextProvider: DiagnosticsContextProvider,
    networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
    runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    serviceStateStore: ServiceStateStore,
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
    deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore = DefaultDeviceRuntimeEvidenceStore(),
    logcatSnapshotCollector: LogcatSnapshotCollector = LogcatSnapshotCollector(),
    diagnosticsHistoryClock: DiagnosticsHistoryClock = TestDiagnosticsHistoryClock(),
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(stores, diagnosticsHistoryClock),
    networkEdgePreferenceStore: NetworkEdgePreferenceStore =
        DefaultNetworkEdgePreferenceStore(stores, diagnosticsHistoryClock),
    networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore =
        DefaultNetworkDnsPathPreferenceStore(stores, diagnosticsHistoryClock),
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
            exportRecordStore = stores,
            sourceLoader =
                DiagnosticsArchiveSourceLoader(
                    appSettingsRepository = appSettingsRepository,
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    artifactQueryStore = stores,
                    archiveEventQueryStore = stores,
                    bypassUsageHistoryStore = stores,
                    logcatSnapshotCollector = logcatSnapshotCollector,
                    fileLogWriter =
                        FileLogWriter(
                            java.nio.file.Files
                                .createTempDirectory("file-log-test")
                                .toFile(),
                        ),
                    startupJournal = NoopStartupJournal,
                    buildInfoProvider =
                        object : DiagnosticsArchiveBuildInfoProvider {
                            override fun buildProvenance(): DiagnosticsArchiveBuildProvenance =
                                DiagnosticsArchiveBuildProvenance(
                                    applicationId = context.packageName,
                                    appVersionName = "0.0.1-test",
                                    appVersionCode = 1L,
                                    buildType = "debug",
                                    gitCommit = "test-commit",
                                    nativeLibraries =
                                        listOf(
                                            DiagnosticsArchiveNativeLibraryProvenance(
                                                name = "libripdpi.so",
                                                version = "test-native",
                                            ),
                                            DiagnosticsArchiveNativeLibraryProvenance(
                                                name = "libripdpi-tunnel.so",
                                                version = "test-native",
                                            ),
                                        ),
                                )
                        },
                    diagnosticsHomeCompositeRunService = FakeDiagnosticsHomeCompositeRunService(),
                    replayResultStore = ReplayResultStore(),
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
                    projector = DiagnosticsSummaryProjector(),
                    replayArchiveEntryBuilder =
                        ReplayArchiveEntryBuilder(
                            ReplayArchiveRedactor(),
                            DiagnosticsArchiveClock { System.currentTimeMillis() },
                            json,
                        ),
                    json = json,
                    serviceStateStore = FakeServiceStateStore(),
                ),
            fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = context.cacheDir,
                    clock = DiagnosticsArchiveClock { System.currentTimeMillis() },
                ),
            zipWriter = DiagnosticsArchiveZipWriter(),
            idGenerator =
                DiagnosticsArchiveIdGenerator {
                    java.util.UUID
                        .randomUUID()
                        .toString()
                },
            developerAnalyticsSource = NoopDeveloperAnalyticsSource,
        ),
    serverCapabilityStore: FakeServerCapabilityStore = FakeServerCapabilityStore(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    controllerScope: CoroutineScope = scope,
    bridgeMutex: Mutex = Mutex(),
    retirementQueue: BridgeRetirementQueue = testBridgeRetirementQueue(scope),
): DiagnosticsServicesBundle {
    lateinit var scanController: DefaultDiagnosticsScanController
    val mapper = DiagnosticsBoundaryMapper(json)
    val timelineSource =
        DefaultDiagnosticsTimelineSource(
            profileCatalog = stores,
            scanRecordStore = stores,
            artifactReadStore = stores,
            bypassUsageHistoryStore = stores,
            mapper = mapper,
            scope = scope,
            json = json,
        )
    val requestFactory =
        DiagnosticsScanRequestFactory(
            context = context,
            networkMetadataProvider = networkMetadataProvider,
            intentResolver = DefaultDiagnosticsIntentResolver(stores, appSettingsRepository, json),
            scanContextCollector =
                DefaultScanContextCollector(
                    profileCatalog = stores,
                    networkFingerprintProvider = networkFingerprintProvider,
                    nativeNetworkSnapshotProvider = nativeNetworkSnapshotProvider,
                    diagnosticsContextProvider = diagnosticsContextProvider,
                    networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
                    networkEdgePreferenceStore = networkEdgePreferenceStore,
                    serviceStateStore = serviceStateStore,
                    json = json,
                ),
            diagnosticsPlanner = DefaultDiagnosticsPlanner(),
            engineRequestEncoder = DefaultEngineRequestEncoder(),
            activeProbeSafetyPolicy = ActiveProbeSafetyPolicy(),
            json = json,
        )
    val activeScanRegistry = ActiveScanRegistry(timelineSource, bridgeMutex)
    val scanAdmissionService = ScanAdmissionService(appSettingsRepository, stores, activeScanRegistry, json)
    val bridgeExecutionService =
        BridgeExecutionService(
            networkDiagnosticsBridgeFactory = networkDiagnosticsBridgeFactory,
            activeScanRegistry = activeScanRegistry,
            retirementQueue = retirementQueue,
        )
    val passiveEventPersistenceService = PassiveEventPersistenceService(stores, json)
    val rawPathSettlementBarrier = RawPathSettlementBarrier(stores, stores.rawPathSettlementStore, json)
    val executionCoordinator =
        DiagnosticsScanExecutionCoordinator(
            scanRecordStore = stores,
            activeScanRegistry = activeScanRegistry,
            bridgeExecutionService = bridgeExecutionService,
            bridgePollingService = BridgePollingService(passiveEventPersistenceService, json),
            scanFinalizationService =
                ScanFinalizationService(
                    context = context,
                    scanRecordStore = stores,
                    artifactWriteStore = stores,
                    networkMetadataProvider = networkMetadataProvider,
                    networkFingerprintProvider = networkFingerprintProvider,
                    diagnosticsContextProvider = diagnosticsContextProvider,
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = resolverOverrideStore,
                    rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                    networkEdgePreferenceStore = networkEdgePreferenceStore,
                    networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
                    serverCapabilityStore = serverCapabilityStore,
                    rawPathSettlementBarrier = rawPathSettlementBarrier,
                    json = json,
                ),
            scanRequestFactory = requestFactory,
            serviceStateStore = serviceStateStore,
            runtimeCoordinator = runtimeCoordinator,
        )
    val scheduler =
        AutomaticProbeScheduler(
            appSettingsRepository = appSettingsRepository,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            diagnosticsArtifactReadStore = stores,
            policyHandoverEventStore = policyHandoverEventStore,
            launcherProvider =
                object : Provider<AutomaticProbeLauncher> {
                    override fun get(): AutomaticProbeLauncher = scanController
                },
            activeProbeSafetyPolicy =
                ActiveProbeSafetyPolicy(
                    automaticHandoverProbeDelayMs = automaticHandoverProbeDelayMs,
                    automaticHandoverProbeCooldownMs = automaticHandoverProbeCooldownMs,
                    automaticStrategyFailureProbeCooldownMs = automaticHandoverProbeCooldownMs,
                ),
            scope = scope,
        )
    val recommendationStore = DiagnosticsRecommendationStore(stores, json)
    val runtimeHistoryStartup =
        createRuntimeHistoryMonitor(
            appSettingsRepository = appSettingsRepository,
            stores = stores,
            networkMetadataProvider = networkMetadataProvider,
            diagnosticsContextProvider = diagnosticsContextProvider,
            serviceStateStore = serviceStateStore,
            diagnosticsHistoryClock = diagnosticsHistoryClock,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
            deviceRuntimeEvidenceStore = deviceRuntimeEvidenceStore,
            scope = scope,
        )
    scanController =
        DefaultDiagnosticsScanController(
            appSettingsRepository = appSettingsRepository,
            scanRecordStore = stores,
            artifactWriteStore = stores,
            runtimeCoordinator = runtimeCoordinator,
            serviceStateStore = serviceStateStore,
            scanRequestFactory = requestFactory,
            scanAdmissionService = scanAdmissionService,
            activeScanRegistry = activeScanRegistry,
            bridgeExecutionService = bridgeExecutionService,
            executionCoordinator = executionCoordinator,
            hiddenProbeConflictRequestFactory = HiddenProbeConflictRequestFactory(json),
            scope = controllerScope,
        )
    return DiagnosticsServicesBundle(
        bootstrapper =
            DefaultDiagnosticsBootstrapper(
                archiveExporter = archiveExporter,
                profileImporter =
                    BundledDiagnosticsProfileImporter(
                        profileSource = AssetBundledDiagnosticsProfileSource(context),
                        overrideSource = EmptyBundledDiagnosticsCatalogOverrideSource,
                        profileCatalog = stores,
                        clock = diagnosticsHistoryClock,
                        json = json,
                    ),
                runtimeHistoryStartup = runtimeHistoryStartup,
                policyHandoverEventStore = policyHandoverEventStore,
                automaticProbeScheduler = scheduler,
                rawPathSettlementBarrier = rawPathSettlementBarrier,
                scanRecordStore = stores,
                importBundledProfilesOnInitialize = importBundledProfilesOnInitialize,
                scope = scope,
            ),
        timelineSource = timelineSource,
        scanController = scanController,
        detailLoader =
            DefaultDiagnosticsDetailLoader(
                scanRecordStore = stores,
                artifactQueryStore = stores,
                bypassUsageHistoryStore = stores,
                serverCapabilityStore = serverCapabilityStore,
                mapper = mapper,
                json = json,
            ),
        shareService =
            DefaultDiagnosticsShareService(
                stores,
                stores,
                stores,
                archiveExporter,
                json,
                serviceStateStore = FakeServiceStateStore(),
            ),
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

internal fun testBridgeRetirementQueue(scope: CoroutineScope): BridgeRetirementQueue =
    BridgeRetirementQueue(
        requireNotNull(scope.coroutineContext[ContinuationInterceptor]) as CoroutineDispatcher,
    )

internal fun createRuntimeHistoryMonitor(
    appSettingsRepository: AppSettingsRepository,
    stores: FakeDiagnosticsHistoryStores,
    networkMetadataProvider: NetworkMetadataProvider,
    diagnosticsContextProvider: DiagnosticsContextProvider,
    serviceStateStore: ServiceStateStore,
    diagnosticsHistoryClock: DiagnosticsHistoryClock = TestDiagnosticsHistoryClock(),
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(stores, diagnosticsHistoryClock),
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
    deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore = DefaultDeviceRuntimeEvidenceStore(),
    policyHandoverEventStore: PolicyHandoverEventStore = FakePolicyHandoverEventStore(),
    networkPathValidationSource: NetworkPathValidationSource =
        object : NetworkPathValidationSource {
            override val evidence =
                MutableStateFlow(NetworkPathValidationEvidence(captureStatus = "test_unavailable"))
        },
    networkTransitionFlush: (suspend (NetworkTransitionAdmission) -> Boolean)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
): RuntimeHistoryStartup {
    val rememberedPolicySessionTracker =
        RememberedPolicySessionTracker(rememberedNetworkPolicyStore, policyHandoverEventStore)
    val artifactPersister =
        RuntimeArtifactPersister(
            artifactReadStore = stores,
            artifactWriteStore = stores,
            failureArtifactWriteStore = stores,
            historyRetentionStore = stores,
            networkMetadataProvider = networkMetadataProvider,
            diagnosticsContextProvider = diagnosticsContextProvider,
            serviceStateStore = serviceStateStore,
            nativeMemoryProbe = { NativeMemorySample(nativeHeapBytes = 0, processRssBytes = 0) },
        )
    val deviceStateEventRecorder =
        DefaultDeviceStateEventRecorder(
            provider = FakeDeviceStateProvider(),
            artifactWriteStore = stores,
            clock = TestDeviceStateEventClock(),
            scope = scope,
        )
    val sessionCoordinator =
        RuntimeSessionCoordinator(
            appSettingsRepository = appSettingsRepository,
            profileCatalog = stores,
            bypassUsageHistoryStore = stores,
            terminalOutboxStore = stores,
            rememberedNetworkPolicyRecordStore = stores,
            diagnosticsContextProvider = diagnosticsContextProvider,
            serviceStateStore = serviceStateStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
            rememberedPolicySessionTracker = rememberedPolicySessionTracker,
            artifactPersister = artifactPersister,
            deviceStateEventRecorder = deviceStateEventRecorder,
            scope = scope,
        )
    networkTransitionFlush?.let(sessionCoordinator::registerNetworkTransitionFlush)
    return RuntimeHistoryMonitor(
        serviceStateStore = serviceStateStore,
        activeConnectionPolicyStore = activeConnectionPolicyStore,
        deviceRuntimeEvidenceStore = deviceRuntimeEvidenceStore,
        networkPathValidationSource = networkPathValidationSource,
        sessionCoordinator = sessionCoordinator,
        scope = scope,
    )
}

internal class FakeDeviceStateProvider(
    var snapshot: DeviceStateSnapshot = deviceStateSnapshotForTest(),
) : DeviceStateProvider {
    private var onChanged: (() -> Unit)? = null
    val isObserving: Boolean
        get() = onChanged != null

    override fun capture(): DeviceStateSnapshot = snapshot

    override fun observeChanges(onChanged: () -> Unit): DeviceStateObservation {
        this.onChanged = onChanged
        return DeviceStateObservation { this.onChanged = null }
    }

    fun emitChanged() {
        onChanged?.invoke()
    }
}

internal class TestDeviceStateEventClock(
    var currentTime: Long = 1_000L,
) : DeviceStateEventClock {
    override fun now(): Long = currentTime++
}

@Suppress("LongParameterList")
internal fun deviceStateSnapshotForTest(
    screenInteractive: DeviceStateValue = DeviceStateValue.Enabled,
    deviceIdle: DeviceStateValue = DeviceStateValue.Disabled,
    powerSaver: DeviceStateValue = DeviceStateValue.Disabled,
    backgroundRestricted: DeviceStateValue = DeviceStateValue.Disabled,
    batteryOptimizationExempt: DeviceStateValue = DeviceStateValue.Enabled,
    lowPowerStandby: DeviceStateValue = DeviceStateValue.Disabled,
    lowPowerStandbyExempt: DeviceStateValue = DeviceStateValue.Enabled,
    batteryLevel: DeviceBatteryBand = DeviceBatteryBand.High,
    charging: DeviceStateValue = DeviceStateValue.Disabled,
    standbyBucket: DeviceStandbyBucket = DeviceStandbyBucket.Active,
    notificationPermission: DeviceStateValue = DeviceStateValue.Enabled,
    notificationsAllowed: DeviceStateValue = DeviceStateValue.Enabled,
    notificationsPaused: DeviceStateValue = DeviceStateValue.Disabled,
    foregroundNotificationActive: DeviceStateValue = DeviceStateValue.Enabled,
    foregroundNotificationChannels: NotificationChannelState = NotificationChannelState.Enabled,
    foregroundServiceType: ForegroundServiceTypeBand = ForegroundServiceTypeBand.SpecialUse,
    userUnlocked: DeviceStateValue = DeviceStateValue.Enabled,
    processImportance: ProcessImportanceBand = ProcessImportanceBand.ForegroundService,
    memoryPressure: MemoryPressureBand = MemoryPressureBand.None,
    thermalStatus: DeviceThermalBand = DeviceThermalBand.None,
    manufacturerFamily: DeviceManufacturerFamily = DeviceManufacturerFamily.Other,
): DeviceStateSnapshot =
    DeviceStateSnapshot(
        screenInteractive = screenInteractive,
        deviceIdle = deviceIdle,
        powerSaver = powerSaver,
        backgroundRestricted = backgroundRestricted,
        batteryOptimizationExempt = batteryOptimizationExempt,
        lowPowerStandby = lowPowerStandby,
        lowPowerStandbyExempt = lowPowerStandbyExempt,
        batteryLevel = batteryLevel,
        charging = charging,
        standbyBucket = standbyBucket,
        notificationPermission = notificationPermission,
        notificationsAllowed = notificationsAllowed,
        notificationsPaused = notificationsPaused,
        foregroundNotificationActive = foregroundNotificationActive,
        foregroundNotificationChannels = foregroundNotificationChannels,
        foregroundServiceType = foregroundServiceType,
        userUnlocked = userUnlocked,
        processImportance = processImportance,
        memoryPressure = memoryPressure,
        thermalStatus = thermalStatus,
        manufacturerFamily = manufacturerFamily,
    )

private class EmptyActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
    override val activePolicies: StateFlow<Map<com.poyka.ripdpi.data.Mode, ActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}

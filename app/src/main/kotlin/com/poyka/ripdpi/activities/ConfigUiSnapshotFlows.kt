package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val ConfigUiStateStopTimeoutMillis = 5_000L

internal data class ConfigPersistedSnapshot(
    val settings: AppSettings,
    val relayPresets: List<RelayPresetDefinition>,
    val relayProfileRecords: List<RelayProfileRecord>,
)

internal data class ConfigRuntimeSnapshot(
    val serviceStatus: Pair<AppStatus, Mode>,
    val latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
    val networkSnapshot: NativeNetworkSnapshot?,
    val capabilityRecords: List<ServerCapabilityRecord>,
)

internal fun configPersistedSnapshots(
    appSettingsRepository: AppSettingsRepository,
    relayPresetCatalog: RelayPresetCatalog,
    relayArtifacts: ConfigRelayArtifactRepository,
    ioDispatcher: CoroutineDispatcher,
): Flow<ConfigPersistedSnapshot> =
    appSettingsRepository.settings
        .map { settings ->
            ConfigPersistedSnapshot(
                settings = settings,
                relayPresets = relayPresetCatalog.all(),
                relayProfileRecords = loadRelayProfileRecordsOrEmpty { relayArtifacts.listProfiles() },
            )
        }.flowOn(ioDispatcher)

internal fun configRuntimeSnapshots(
    serviceStateStore: ServiceStateStore,
    latestDirectModeOutcomeStore: LatestDirectModeOutcomeStore,
    networkSnapshotProvider: NativeNetworkSnapshotProvider,
    capabilityObserver: ConfigCapabilityObserver,
    ioDispatcher: CoroutineDispatcher,
): Flow<ConfigRuntimeSnapshot> =
    combine(
        serviceStateStore.status,
        latestDirectModeOutcomeStore.outcome,
        serviceStateStore.telemetry
            .map { telemetry -> telemetry.networkHandoverState }
            .distinctUntilChanged(),
    ) { serviceStatus, latestDirectModeOutcome, _ ->
        ConfigRuntimeSnapshot(
            serviceStatus = serviceStatus,
            latestDirectModeOutcome = latestDirectModeOutcome,
            networkSnapshot = runCatching { networkSnapshotProvider.capture() }.getOrNull(),
            capabilityRecords = capabilityObserver.relayCapabilitiesForCurrentNetwork(),
        )
    }.flowOn(ioDispatcher)

internal fun configUiStateFlow(
    scope: CoroutineScope,
    persistedSnapshots: Flow<ConfigPersistedSnapshot>,
    editorState: Flow<ConfigEditorState>,
    serviceTelemetry: Flow<ServiceTelemetrySnapshot>,
    runtimeSnapshots: Flow<ConfigRuntimeSnapshot>,
    factory: ConfigUiStateFactory,
): StateFlow<ConfigUiState> =
    combine(
        persistedSnapshots,
        editorState,
        serviceTelemetry,
        runtimeSnapshots,
    ) { persistedSnapshot, editor, telemetry, runtimeSnapshot ->
        factory.create(
            settings = persistedSnapshot.settings,
            editorState = editor,
            serviceStatus = runtimeSnapshot.serviceStatus,
            serviceTelemetry = telemetry,
            latestDirectModeOutcome = runtimeSnapshot.latestDirectModeOutcome,
            relayPresets = persistedSnapshot.relayPresets,
            relayProfileRecords = persistedSnapshot.relayProfileRecords,
            networkSnapshot = runtimeSnapshot.networkSnapshot,
            capabilityRecords = runtimeSnapshot.capabilityRecords,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(ConfigUiStateStopTimeoutMillis),
        initialValue = ConfigUiState(isLoading = true),
    )

internal fun configEditorStateFlow(
    editorSession: Flow<ConfigEditorSession>,
    activeSaveRequest: Flow<ConfigSaveRequest?>,
    importOperations: Flow<Set<MasqueImportOperation>>,
    recoveryPending: Flow<Boolean>,
    recoveryPersistenceError: Flow<Boolean>,
): Flow<ConfigEditorState> =
    combine(
        editorSession,
        activeSaveRequest,
        importOperations,
        recoveryPending,
        recoveryPersistenceError,
    ) { session, saveRequest, imports, pending, persistenceError ->
        ConfigEditorState(
            session = session,
            saveInFlight = saveRequest != null,
            importInFlight = imports.any { operation -> operation.sessionId == session.sessionId },
            recoveryPending = pending,
            recoveryPersistenceError = persistenceError,
        )
    }

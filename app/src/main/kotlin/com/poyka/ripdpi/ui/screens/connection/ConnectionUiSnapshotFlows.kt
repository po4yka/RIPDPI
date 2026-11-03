package com.poyka.ripdpi.ui.screens.connection

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
import com.poyka.ripdpi.services.RunningReconnectState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val ConnectionUiStateStopTimeoutMillis = 5_000L

internal data class ConnectionPersistedSnapshot(
    val settings: AppSettings,
    val relayPresets: List<RelayPresetDefinition>,
    val relayProfileRecords: List<RelayProfileRecord>,
)

internal data class ConnectionRuntimeSnapshot(
    val serviceStatus: Pair<AppStatus, Mode>,
    val latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
    val networkSnapshot: NativeNetworkSnapshot?,
    val capabilityRecords: List<ServerCapabilityRecord>,
)

internal fun configPersistedSnapshots(
    appSettingsRepository: AppSettingsRepository,
    relayPresetCatalog: RelayPresetCatalog,
    relayArtifacts: ConnectionRelayArtifactRepository,
    ioDispatcher: CoroutineDispatcher,
): Flow<ConnectionPersistedSnapshot> =
    appSettingsRepository.settings
        .map { settings ->
            ConnectionPersistedSnapshot(
                settings = settings,
                relayPresets = relayPresetCatalog.all(),
                relayProfileRecords = loadRelayProfileRecordsOrEmpty { relayArtifacts.listProfiles() },
            )
        }.flowOn(ioDispatcher)

internal fun configRuntimeSnapshots(
    serviceStateStore: ServiceStateStore,
    latestDirectModeOutcomeStore: LatestDirectModeOutcomeStore,
    networkSnapshotProvider: NativeNetworkSnapshotProvider,
    capabilityObserver: ConnectionCapabilityObserver,
    ioDispatcher: CoroutineDispatcher,
): Flow<ConnectionRuntimeSnapshot> =
    combine(
        serviceStateStore.status,
        latestDirectModeOutcomeStore.outcome,
        serviceStateStore.telemetry
            .map { telemetry -> telemetry.networkHandoverState }
            .distinctUntilChanged(),
    ) { serviceStatus, latestDirectModeOutcome, _ ->
        ConnectionRuntimeSnapshot(
            serviceStatus = serviceStatus,
            latestDirectModeOutcome = latestDirectModeOutcome,
            networkSnapshot = runCatching { networkSnapshotProvider.capture() }.getOrNull(),
            capabilityRecords = capabilityObserver.relayCapabilitiesForCurrentNetwork(),
        )
    }.flowOn(ioDispatcher)

internal fun configUiStateFlow(
    scope: CoroutineScope,
    persistedSnapshots: Flow<ConnectionPersistedSnapshot>,
    editorState: Flow<ConnectionEditorState>,
    serviceTelemetry: Flow<ServiceTelemetrySnapshot>,
    runtimeSnapshots: Flow<ConnectionRuntimeSnapshot>,
    factory: ConnectionUiStateFactory,
    reconnectState: Flow<RunningReconnectState> = flowOf(RunningReconnectState.Idle),
): StateFlow<ConnectionUiState> =
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
    }.combine(reconnectState) { state, reconnect ->
        if (reconnect.isConfigPending()) {
            state.copy(
                runtimeOperation = ConnectionRuntimeOperation.Reconnecting,
            )
        } else {
            state
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(ConnectionUiStateStopTimeoutMillis),
        initialValue = ConnectionUiState(isLoading = true),
    )

internal fun configEditorStateFlow(
    editorSession: Flow<ConnectionEditorSession>,
    activeSaveRequest: Flow<ConnectionSaveRequest?>,
    importOperations: Flow<Set<MasqueImportOperation>>,
    recoveryPending: Flow<Boolean>,
    recoveryPersistenceError: Flow<Boolean>,
): Flow<ConnectionEditorState> =
    combine(
        editorSession,
        activeSaveRequest,
        importOperations,
        recoveryPending,
        recoveryPersistenceError,
    ) { session, saveRequest, imports, pending, persistenceError ->
        ConnectionEditorState(
            session = session,
            saveInFlight = saveRequest != null,
            importInFlight = imports.any { operation -> operation.sessionId == session.sessionId },
            recoveryPending = pending,
            recoveryPersistenceError = persistenceError,
        )
    }

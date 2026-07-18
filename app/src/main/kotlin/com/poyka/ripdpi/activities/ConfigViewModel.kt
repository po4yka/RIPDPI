package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.config.relay.resolveRelayPresetSuggestion
import com.poyka.ripdpi.config.relay.toUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private data class ConfigPersistedSnapshot(
    val settings: AppSettings,
    val relayPresets: List<RelayPresetDefinition>,
    val relayProfileRecords: List<RelayProfileRecord>,
)

private data class ConfigRuntimeSnapshot(
    val serviceStatus: Pair<AppStatus, Mode>,
    val latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
    val networkSnapshot: NativeNetworkSnapshot?,
    val capabilityRecords: List<ServerCapabilityRecord>,
)

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        dependencies: ConfigViewModelDependencies,
        importDependencies: ConfigImportDependencies,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val appSettingsRepository = dependencies.appSettingsRepository
        private val relayArtifacts = dependencies.relayArtifacts
        private val relayPresetCatalog = dependencies.relayPresetCatalog
        private val networkSnapshotProvider = dependencies.networkSnapshotProvider
        private val serviceStateStore = dependencies.serviceStateStore
        private val serviceController = dependencies.serviceController
        private val latestDirectModeOutcomeStore = dependencies.latestDirectModeOutcomeStore
        private val capabilityObserver = dependencies.capabilityObserver
        private val dispatchers = dependencies.dispatchers
        private val masqueClientCredentialImporter = importDependencies.masqueClientCredentialImporter
        private val masquePrivacyPassAvailability = importDependencies.masquePrivacyPassAvailability
        private val log = Logger.withTag(LogTags.UI)
        private val editorSession = MutableStateFlow(ConfigEditorSession())
        internal val masqueImports =
            ConfigMasqueImportController(
                importCertificateChain = ::importRelayMasqueCertificateChain,
                importPrivateKey = ::importRelayMasquePrivateKey,
                importPkcs12 = ::importRelayMasquePkcs12,
            )
        internal val masqueImportState = masqueImports.state
        private val editorSessionIds = AtomicLong()
        private val activeSaveRequest = MutableStateFlow<ConfigSaveRequest?>(null)
        private val activeSaveJob = AtomicReference<Job?>()
        private val editorOperationLock = Any()
        private val masqueImportGenerations = ConfigMasqueImportGenerationTracker()
        private val masqueImportController =
            ConfigMasqueImportController(
                scope = viewModelScope,
                importer = masqueClientCredentialImporter,
                beginOperation = ::beginMasqueImport,
                applyDraft = ::updateDraftForMasqueImport,
                reportFailure = ::emitImportFailureIfCurrent,
            )
        private val editorState =
            combine(editorSession, activeSaveRequest) { session, saveRequest ->
                ConfigEditorState(
                    session = session,
                    saveInFlight = saveRequest != null,
                )
            }
        private val supportsMasquePrivacyPass = masquePrivacyPassAvailability.isAvailable()
        private val masquePrivacyPassBuildStatus = masquePrivacyPassAvailability.buildStatus()

        private val _effects =
            MutableSharedFlow<ConfigEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val effects = _effects.bufferForUiLifecycle(viewModelScope)

        init {
            observeCapabilityEvidence()
        }

        private val persistedSnapshots =
            appSettingsRepository.settings
                .map { settings ->
                    ConfigPersistedSnapshot(
                        settings = settings,
                        relayPresets = relayPresetCatalog.all(),
                        relayProfileRecords = runCatching { relayArtifacts.listProfiles() }.getOrDefault(emptyList()),
                    )
                }.flowOn(dispatchers.io)

        private val runtimeSnapshots =
            combine(
                serviceStateStore.status,
                latestDirectModeOutcomeStore.outcome,
                serviceStateStore.telemetry
                    .map { telemetry -> telemetry.networkHandoverState }
                    .distinctUntilChanged(),
            ) { serviceStatus, latestDirectModeOutcome, _ ->
                val networkSnapshot = runCatching { networkSnapshotProvider.capture() }.getOrNull()
                val capabilityRecords = capabilityObserver.relayCapabilitiesForCurrentNetwork()
                ConfigRuntimeSnapshot(
                    serviceStatus = serviceStatus,
                    latestDirectModeOutcome = latestDirectModeOutcome,
                    networkSnapshot = networkSnapshot,
                    capabilityRecords = capabilityRecords,
                )
            }.flowOn(dispatchers.io)

        val uiState: StateFlow<ConfigUiState> =
            combine(
                persistedSnapshots,
                editorState,
                serviceStateStore.telemetry,
                runtimeSnapshots,
            ) { persistedSnapshot, editorState, serviceTelemetry, runtimeSnapshot ->
                val session = editorState.session
                val settings = persistedSnapshot.settings
                val relayProfileRecords = persistedSnapshot.relayProfileRecords
                val currentDraft =
                    sanitizeMasqueAuthModeForCurrentBuild(
                        draft = settings.toConfigDraft(),
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    )
                val draft =
                    sanitizeMasqueAuthModeForCurrentBuild(
                        draft = session.draft ?: currentDraft,
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    )
                val presets = buildConfigPresets(currentDraft)
                val relayProfiles =
                    buildRelayProfileOptions(
                        records = relayProfileRecords,
                        chainProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId },
                    )
                val vpnProfiles = buildVpnProfileOptions(relayProfileRecords)
                val editingPreset =
                    session.presetId?.let { presetId ->
                        presets.firstOrNull { it.id == presetId }?.copy(draft = draft)
                            ?: ConfigPreset(
                                id = presetId,
                                kind = ConfigPresetKind.Custom,
                                draft = draft,
                            )
                    }

                ConfigUiState(
                    activeMode = currentDraft.mode,
                    runningMode =
                        runtimeSnapshot.serviceStatus.second.takeIf {
                            runtimeSnapshot.serviceStatus.first == AppStatus.Running
                        },
                    uiPersona = settings.uiPersona.ifBlank { "simple" },
                    presets = presets,
                    editingPreset = editingPreset,
                    draft = draft,
                    validationErrors =
                        validateConfigDraft(
                            draft = draft,
                            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                            relayProfiles = relayProfileRecords,
                        ),
                    relayProfiles = relayProfiles,
                    vpnProfiles = vpnProfiles,
                    relayChainTrustWarning = resolveRelayChainTrustWarning(draft, relayProfiles),
                    relayChainHopStatus = buildRelayChainHopStatus(serviceTelemetry.relayTelemetry),
                    relayPresets =
                        persistedSnapshot.relayPresets
                            .map { preset ->
                                RelayPresetUiState(
                                    id = preset.id,
                                    title = preset.title,
                                    selected = draft.relayPresetId == preset.id,
                                )
                            }.toImmutableList(),
                    relayPresetSuggestion =
                        resolveRelayPresetSuggestion(
                            heuristicSuggestion =
                                relayPresetCatalog.suggestFor(
                                    runtimeSnapshot.networkSnapshot,
                                    runtimeSnapshot.capabilityRecords,
                                ),
                            serviceTelemetry = serviceTelemetry,
                            capabilityRecords = runtimeSnapshot.capabilityRecords,
                            transportRemediation =
                                recommendTransportRemediation(
                                    result = runtimeSnapshot.latestDirectModeOutcome?.result,
                                    reasonCode = runtimeSnapshot.latestDirectModeOutcome?.reasonCode,
                                    transportClass = runtimeSnapshot.latestDirectModeOutcome?.transportClass,
                                ),
                        ).toUiState(draft),
                    supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    masquePrivacyPassBuildStatus = masquePrivacyPassBuildStatus,
                    isEditorDirty = session.isDirty,
                    isEditorLoading = session.hydrationPending,
                    isEditorSaving = editorState.saveInFlight,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConfigUiState(isLoading = true),
            )

        fun selectMode(mode: Mode) {
            activeSaveJob.get()?.let { saveJob ->
                suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                viewModelScope.launch {
                    saveJob.join()
                    selectMode(mode)
                }
                return
            }
            editorSession.update { current ->
                current.copy(draft = current.draft?.copy(mode = mode))
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    setRipdpiMode(mode.preferenceValue)
                }
            }
        }

        fun toggleRuntimeMode(
            mode: Mode,
            enabled: Boolean,
        ) {
            activeSaveJob.get()?.let { saveJob ->
                suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                viewModelScope.launch {
                    saveJob.join()
                    toggleRuntimeMode(mode, enabled)
                }
                return
            }
            if (enabled) {
                startRuntimeMode(mode)
            } else {
                stopRuntimeMode(mode)
            }
        }

        private fun startRuntimeMode(mode: Mode) {
            when (val result = serviceController.start(mode)) {
                is ServiceStartResult.Accepted -> {
                    return
                }

                is ServiceStartResult.Rejected -> {
                    val senderName = result.mode.startSenderName(stringResolver)
                    val message =
                        stringResolver.getString(R.string.failed_to_start, senderName) +
                            ": " +
                            result.reason.displayMessage(stringResolver)
                    _effects.tryEmit(ConfigEffect.Message(message))
                }
            }
        }

        private fun stopRuntimeMode(mode: Mode) {
            if (serviceStateStore.status.value == AppStatus.Running to mode) {
                serviceController.stop()
            }
        }

        fun selectPreset(presetId: String) {
            val saveJob = activeSaveJob.get()
            if (saveJob != null) {
                suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                viewModelScope.launch {
                    saveJob.join()
                    selectPreset(presetId)
                }
            } else {
                val preset = uiState.value.presets.firstOrNull { it.id == presetId }
                when (preset?.kind) {
                    ConfigPresetKind.Custom -> {
                        startEditingPreset(presetId)
                    }

                    null -> {
                    }

                    else -> {
                        val expectedSession = editorSession.value
                        viewModelScope.launch {
                            appSettingsRepository.update {
                                applyConfigDraft(preset.draft)
                            }
                            editorSession.compareAndSet(expectedSession, ConfigEditorSession())
                        }
                    }
                }
            }
        }

        fun startEditingPreset(presetId: String = "custom") {
            activeSaveJob.get()?.let { saveJob ->
                suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                viewModelScope.launch {
                    saveJob.join()
                    startEditingPreset(presetId)
                }
                return
            }
            val preset = uiState.value.presets.firstOrNull { it.id == presetId }
            val draft = preset?.draft ?: uiState.value.draft
            val sessionId = editorSessionIds.incrementAndGet()
            val session =
                ConfigEditorSession(
                    sessionId = sessionId,
                    presetId = presetId,
                    draft = draft,
                    hydrationPending = true,
                )
            editorSession.value = session
            viewModelScope.launch {
                val hydration = runCatching { relayArtifacts.hydrate(draft) }
                val error = hydration.exceptionOrNull()
                if (error == null) {
                    editorSession.compareAndSet(
                        expect = session,
                        update = session.completeHydration(sessionId, hydration.getOrThrow()),
                    )
                } else {
                    val recovered =
                        editorSession.compareAndSet(
                            expect = session,
                            update = session.completeHydration(sessionId, draft),
                        )
                    if (error is CancellationException) {
                        throw error
                    } else if (recovered) {
                        log.e(error) { "Failed to hydrate mode editor relay artifacts" }
                        _effects.emit(ConfigEffect.Message(stringResolver.getString(R.string.update_error_unknown)))
                    }
                }
            }
        }

        fun updateDraft(transform: ConfigDraft.() -> ConfigDraft) {
            editorSession.update { current ->
                when {
                    current.hydrationPending -> {
                        current
                    }

                    current.presetId != null -> {
                        current.copy(
                            draft = requireNotNull(current.draft).transform(),
                            draftRevision = current.draftRevision + 1,
                        )
                    }

                    else -> {
                        val baseDraft = uiState.value.draft
                        ConfigEditorSession(
                            sessionId = editorSessionIds.incrementAndGet(),
                            presetId = "custom",
                            baselineDraft = baseDraft,
                            draft = baseDraft.transform(),
                            draftRevision = 1L,
                        )
                    }
                }
            }
        }

        fun applyRelayPreset(presetId: String) {
            val preset = relayPresetCatalog.find(presetId) ?: return
            updateDraft {
                applyRelayPresetDefinition(preset)
            }
        }

        fun updateChainDsl(value: String) {
            updateDraft { withChainDsl(value) }
        }

        fun cancelEditing(): Boolean =
            synchronized(editorOperationLock) {
                if (activeSaveRequest.value != null || activeSaveJob.get() != null) {
                    false
                } else {
                    editorSession.value = ConfigEditorSession()
                    clearMasqueImportState()
                    true
                }
            }

        fun requestEditorExit(): ConfigEditorExitDecision =
            synchronized(editorOperationLock) {
                when {
                    activeSaveRequest.value != null || activeSaveJob.get() != null -> {
                        ConfigEditorExitDecision.Blocked
                    }

                    editorSession.value.isDirty -> {
                        ConfigEditorExitDecision.ConfirmDiscard
                    }

                    else -> {
                        editorSession.value = ConfigEditorSession()
                        ConfigEditorExitDecision.Exit
                    }
                }
            }

        internal fun currentEditorSessionId(): Long? =
            editorSession.value
                .takeIf { current -> current.presetId != null && !current.hydrationPending }
                ?.sessionId

        fun importRelayMasqueCertificateChain(
            uri: Uri,
            expectedSessionId: Long,
        ) = masqueImportController.importCertificateChain(uri, expectedSessionId)

        fun importRelayMasquePrivateKey(
            uri: Uri,
            expectedSessionId: Long,
        ) = masqueImportController.importPrivateKey(uri, expectedSessionId)

        fun importRelayMasquePkcs12(
            uri: Uri,
            password: String?,
            expectedSessionId: Long,
        ) = masqueImportController.importPkcs12(uri, password, expectedSessionId)

        fun saveDraft() {
            val (saveJob, request) =
                synchronized(editorOperationLock) {
                    val request =
                        beginConfigSave(
                            editorSession = editorSession,
                            activeSaveRequest = activeSaveRequest,
                            fallbackDraft = uiState.value.draft,
                        ) ?: return
                    val job =
                        viewModelScope.launch(start = CoroutineStart.LAZY) {
                            saveDraft(request)
                        }
                    if (!activeSaveJob.compareAndSet(null, job)) {
                        activeSaveRequest.compareAndSet(request, null)
                        return
                    }
                    job to request
                }
            saveJob.invokeOnCompletion {
                synchronized(editorOperationLock) {
                    activeSaveJob.compareAndSet(saveJob, null)
                    activeSaveRequest.compareAndSet(request, null)
                }
            }
            saveJob.start()
        }

        private suspend fun saveDraft(request: ConfigSaveRequest) {
            val save =
                runCatching {
                    val relayProfileRecords = relayArtifacts.listProfiles()
                    if (
                        validateConfigDraft(
                            draft = request.draft,
                            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                            relayProfiles = relayProfileRecords,
                        ).isNotEmpty()
                    ) {
                        ConfigSaveOutcome.ValidationFailed
                    } else {
                        val persistedDraft = relayArtifacts.prepareForPersistence(request.draft)
                        currentCoroutineContext().ensureActive()
                        if (editorSession.value.sessionId != request.sessionId) {
                            ConfigSaveOutcome.Stale
                        } else {
                            relayArtifacts.persist(persistedDraft)
                            currentCoroutineContext().ensureActive()
                            if (editorSession.value.sessionId != request.sessionId) {
                                ConfigSaveOutcome.Stale
                            } else {
                                applySavedDraftToRunningService(persistedDraft)
                                ConfigSaveOutcome.Saved
                            }
                        }
                    }
                }
            val error = save.exceptionOrNull()
            if (error is CancellationException) {
                clearConfigSavePending(editorSession, request)
                throw error
            } else if (error != null) {
                if (clearConfigSavePending(editorSession, request)) {
                    log.e(error) { "Failed to save mode editor draft" }
                    _effects.emit(ConfigEffect.Message(stringResolver.getString(R.string.update_error_unknown)))
                }
            } else {
                when (save.getOrThrow()) {
                    ConfigSaveOutcome.ValidationFailed -> {
                        if (clearConfigSavePending(editorSession, request)) {
                            _effects.emit(ConfigEffect.ValidationFailed)
                        }
                    }

                    ConfigSaveOutcome.Saved -> {
                        if (completeSuccessfulConfigSave(editorSession, request)) {
                            clearMasqueImportState()
                            _effects.emit(ConfigEffect.SaveSuccess)
                        }
                    }

                    ConfigSaveOutcome.Stale -> {
                    }
                }
            }
        }

        fun resetToDefaults() {
            activeSaveJob.get()?.let { saveJob ->
                suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                viewModelScope.launch {
                    saveJob.join()
                    resetToDefaults()
                }
                return
            }
            viewModelScope.launch {
                val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
                appSettingsRepository.update {
                    applyConfigDraft(defaultDraft)
                }
                editorSession.value = ConfigEditorSession()
            }
        }

        private fun updateDraftForSession(
            expectedSessionId: Long,
            transform: ConfigDraft.() -> ConfigDraft,
        ): Boolean {
            while (true) {
                val current = editorSession.value
                if (current.sessionId != expectedSessionId || current.presetId == null || current.hydrationPending) {
                    return false
                }
                val updated =
                    current.copy(
                        draft = requireNotNull(current.draft).transform(),
                        draftRevision = current.draftRevision + 1,
                    )
                if (editorSession.compareAndSet(current, updated)) {
                    return true
                }
            }
        }

        private fun clearMasqueImportState() {
            masqueImports.clear()
        }

        private fun beginMasqueImport(
            expectedSessionId: Long,
            certificate: Boolean = false,
            privateKey: Boolean = false,
        ): MasqueImportOperation? =
            synchronized(editorOperationLock) {
                if (!isCurrentEditorSession(expectedSessionId)) {
                    null
                } else {
                    masqueImportGenerations.begin(
                        sessionId = expectedSessionId,
                        certificate = certificate,
                        privateKey = privateKey,
                    )
                }
            }

        private fun updateDraftForMasqueImport(
            operation: MasqueImportOperation,
            transform: ConfigDraft.() -> ConfigDraft,
        ): Boolean =
            synchronized(editorOperationLock) {
                if (isCurrentMasqueImport(operation)) {
                    updateDraftForSession(operation.sessionId, transform)
                } else {
                    false
                }
            }

        private fun emitImportFailureIfCurrent(
            operation: MasqueImportOperation,
            error: Throwable,
            messageResource: Int,
        ) {
            synchronized(editorOperationLock) {
                if (isCurrentMasqueImport(operation)) {
                    log.e(error) { "Failed to import MASQUE client credential" }
                    _effects.tryEmit(ConfigEffect.Message(stringResolver.getString(messageResource)))
                }
            }
        }

        private fun isCurrentMasqueImport(operation: MasqueImportOperation): Boolean =
            isCurrentEditorSession(operation.sessionId) && masqueImportGenerations.isCurrent(operation)

        private fun isCurrentEditorSession(expectedSessionId: Long): Boolean =
            editorSession.value.let { current ->
                current.sessionId == expectedSessionId && current.presetId != null && !current.hydrationPending
            }

        private fun observeCapabilityEvidence() {
            viewModelScope.launch {
                combine(
                    appSettingsRepository.settings,
                    serviceStateStore.telemetry,
                ) { settings, telemetry ->
                    settings.toConfigDraft() to telemetry
                }.collect { (draft, telemetry) ->
                    capabilityObserver.rememberCapabilityEvidence(draft, telemetry)
                }
            }
        }

        private suspend fun applySavedDraftToRunningService(draft: ConfigDraft) {
            if (serviceStateStore.status.value.first != AppStatus.Running) {
                return
            }
            serviceController.stop()
            val halted =
                withTimeoutOrNull(10.seconds) {
                    serviceStateStore.status.first { it.first == AppStatus.Halted }
                    true
                } == true
            if (halted) {
                startRuntimeMode(draft.mode)
            }
        }
    }

private data class ConfigSaveRequest(
    val sessionId: Long,
    val draftRevision: Long,
    val draft: ConfigDraft,
)

private data class ConfigEditorState(
    val session: ConfigEditorSession,
    val saveInFlight: Boolean,
)

private enum class ConfigSaveOutcome {
    ValidationFailed,
    Saved,
    Stale,
}

private fun beginConfigSave(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    activeSaveRequest: MutableStateFlow<ConfigSaveRequest?>,
    fallbackDraft: ConfigDraft,
): ConfigSaveRequest? {
    var result: ConfigSaveRequest? = null
    var complete = false
    while (!complete) {
        val session = editorSession.value
        val request =
            if (session.hydrationPending || session.savePending || activeSaveRequest.value != null) {
                null
            } else {
                ConfigSaveRequest(
                    sessionId = session.sessionId,
                    draftRevision = session.draftRevision,
                    draft = session.draft ?: fallbackDraft,
                )
            }
        when {
            request == null -> {
                complete = true
            }

            !activeSaveRequest.compareAndSet(null, request) -> {
                complete = true
            }

            editorSession.compareAndSet(session, session.copy(savePending = true)) -> {
                result = request
                complete = true
            }

            else -> {
                activeSaveRequest.compareAndSet(request, null)
            }
        }
    }
    return result
}

private fun clearConfigSavePending(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    request: ConfigSaveRequest,
): Boolean {
    var requestWasCurrent = false
    var complete = false
    while (!complete) {
        val current = editorSession.value
        if (current.sessionId != request.sessionId || !current.savePending) {
            complete = true
        } else if (editorSession.compareAndSet(current, current.copy(savePending = false))) {
            requestWasCurrent = current.draftRevision == request.draftRevision
            complete = true
        }
    }
    return requestWasCurrent
}

private fun completeSuccessfulConfigSave(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    request: ConfigSaveRequest,
): Boolean {
    var shouldNotifySuccess = false
    var complete = false
    while (!complete) {
        val current = editorSession.value
        if (current.sessionId != request.sessionId || !current.savePending) {
            complete = true
        } else {
            val requestIsCurrent = current.draftRevision == request.draftRevision
            val completed =
                if (requestIsCurrent) {
                    ConfigEditorSession()
                } else {
                    current.copy(
                        baselineDraft = request.draft,
                        savePending = false,
                    )
                }
            if (editorSession.compareAndSet(current, completed)) {
                shouldNotifySuccess = requestIsCurrent && !current.suppressSaveSuccess
                complete = true
            }
        }
    }
    return shouldNotifySuccess
}

private fun suppressActiveConfigSaveSuccess(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    activeSaveRequest: MutableStateFlow<ConfigSaveRequest?>,
) {
    val activeSessionId = activeSaveRequest.value?.sessionId
    if (activeSessionId != null) {
        editorSession.update { current ->
            if (current.sessionId == activeSessionId) {
                current.copy(suppressSaveSuccess = true)
            } else {
                current
            }
        }
    }
}

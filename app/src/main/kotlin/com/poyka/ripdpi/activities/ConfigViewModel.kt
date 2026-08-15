package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
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
        private val editorDraftStore = dependencies.editorDraftStore
        private val masqueClientCredentialImporter = importDependencies.masqueClientCredentialImporter
        private val masquePrivacyPassAvailability = importDependencies.masquePrivacyPassAvailability
        private val log = Logger.withTag(LogTags.UI)
        private val editorSession = MutableStateFlow(ConfigEditorSession())
        internal val masqueImports =
            ConfigMasqueImportController(
                importCertificateChain = ::importRelayMasqueCertificateChain,
                importPrivateKey = ::importRelayMasquePrivateKey,
                importPkcs12 = ::importRelayMasquePkcs12,
                initialState = restoreConfigMasqueImportState(savedStateHandle),
                onStateChanged = { state -> persistConfigMasqueImportState(savedStateHandle, state) },
            )
        internal val masqueImportState = masqueImports.state
        internal val editorHydrationFailures = ConfigEditorHydrationFailureHandler(editorSession)
        private val editorSessionIds = AtomicLong()
        private val invalidatedEditorRecoverySessionIds =
            savedStateHandle
                .get<ArrayList<String>>(ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey)
                .orEmpty()
                .filter(::isValidConfigEditorRecoverySessionId)
        private val editorRecoverySessionId =
            MutableStateFlow(
                savedStateHandle
                    .get<String>(ConfigEditorRecoverySessionIdSavedStateKey)
                    ?.takeIf(::isValidConfigEditorRecoverySessionId)
                    ?.takeUnless(invalidatedEditorRecoverySessionIds::contains)
                    ?: newConfigEditorRecoverySessionId().also {
                        savedStateHandle[ConfigEditorRecoverySessionIdSavedStateKey] = it
                    },
            )
        private val editorRecoveryReady = MutableStateFlow(false)
        private val editorRecoveryPending = MutableStateFlow(false)
        private val editorRecoveryPersistenceError = MutableStateFlow(false)
        private val editorRecoveryStoreLock = Mutex()
        private var editorRecoveryRestoreFailed = false
        private var pendingEditorStartPresetId: String? = null
        private val updateEditorRecoverySessionId: (String) -> Unit = { value ->
            savedStateHandle[ConfigEditorRecoverySessionIdSavedStateKey] = value
        }
        private val rememberInvalidatedEditorRecoverySessionId: (String) -> Unit = { value ->
            val existing =
                savedStateHandle
                    .get<ArrayList<String>>(ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey)
                    .orEmpty()
                    .filter(::isValidConfigEditorRecoverySessionId)
            val invalidated =
                (existing + value)
                    .distinct()
                    .takeLast(ConfigEditorMaxInvalidatedSessionIds)
            savedStateHandle[ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey] = ArrayList(invalidated)
        }
        private val activeSaveRequest = MutableStateFlow<ConfigSaveRequest?>(null)
        private val activeSaveJob = AtomicReference<Job?>()
        private val editorOperationLock = Any()
        private val masqueImportGenerations = ConfigMasqueImportGenerationTracker()
        private val supportsMasquePrivacyPass = masquePrivacyPassAvailability.isAvailable()
        private val masquePrivacyPassBuildStatus = masquePrivacyPassAvailability.buildStatus()
        private val uiStateFactory =
            ConfigUiStateFactory(
                dependencies = dependencies,
                supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                masquePrivacyPassBuildStatus = masquePrivacyPassBuildStatus,
            )

        private val _effects =
            MutableSharedFlow<ConfigEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val rotateEditorRecoverySession =
            configEditorRecoveryRotator(
                previousSessionId = { editorRecoverySessionId.value },
                recoveryPending = editorRecoveryPending,
                recoveryReady = editorRecoveryReady,
                persistenceError = editorRecoveryPersistenceError,
                storeLock = editorRecoveryStoreLock,
                store = editorDraftStore,
                onInvalidationFailure = { failure ->
                    log.e(failure) { "Failed to invalidate mode editor recovery" }
                    _effects.emit(ConfigEffect.Message(stringResolver.getString(R.string.update_error_unknown)))
                },
                onInvalidated = rememberInvalidatedEditorRecoverySessionId,
                onRotationSucceeded = {
                    editorRecoveryRestoreFailed = false
                    pendingEditorStartPresetId = null
                },
                newSessionId = ::newConfigEditorRecoverySessionId,
                updateSessionId = { next ->
                    updateEditorRecoverySessionId(next)
                    editorRecoverySessionId.value = next
                },
            )
        private val rotateEditorRecoverySessionAndClearMasqueState =
            clearingConfigEditorRecoveryRotator(rotateEditorRecoverySession, masqueImports::clear)
        private val masqueImportSessionCoordinator =
            ConfigMasqueImportSessionCoordinator(
                editorSession = editorSession,
                operationLock = editorOperationLock,
                generations = masqueImportGenerations,
                log = log,
                effects = _effects,
                stringResolver = stringResolver,
            )
        private val editorState =
            configEditorStateFlow(
                editorSession = editorSession,
                activeSaveRequest = activeSaveRequest,
                importOperations = masqueImportSessionCoordinator.inFlightOperations,
                recoveryPending = editorRecoveryPending,
                recoveryPersistenceError = editorRecoveryPersistenceError,
            )
        private val masqueCredentialImportRunner =
            ConfigMasqueCredentialImportRunner(
                scope = viewModelScope,
                importer = masqueClientCredentialImporter,
                beginOperation = masqueImportSessionCoordinator::begin,
                applyDraft = masqueImportSessionCoordinator::updateDraft,
                reportFailure = masqueImportSessionCoordinator::reportFailure,
                finishOperation = masqueImportSessionCoordinator::finish,
            )
        val effects = _effects.bufferForUiLifecycle(viewModelScope)

        init {
            observeConfigCapabilityEvidence(
                scope = viewModelScope,
                appSettingsRepository = appSettingsRepository,
                serviceStateStore = serviceStateStore,
                capabilityObserver = capabilityObserver,
            )
        }

        private val persistedSnapshots =
            configPersistedSnapshots(
                appSettingsRepository = appSettingsRepository,
                relayPresetCatalog = relayPresetCatalog,
                relayArtifacts = relayArtifacts,
                ioDispatcher = dispatchers.io,
            )

        private val runtimeSnapshots =
            configRuntimeSnapshots(
                serviceStateStore = serviceStateStore,
                latestDirectModeOutcomeStore = latestDirectModeOutcomeStore,
                networkSnapshotProvider = networkSnapshotProvider,
                capabilityObserver = capabilityObserver,
                ioDispatcher = dispatchers.io,
            )

        val uiState: StateFlow<ConfigUiState> =
            configUiStateFlow(
                scope = viewModelScope,
                persistedSnapshots = persistedSnapshots,
                editorState = editorState,
                serviceTelemetry = serviceStateStore.telemetry,
                runtimeSnapshots = runtimeSnapshots,
                factory = uiStateFactory,
            )

        init {
            viewModelScope.launch {
                editorSession.collect { session ->
                    if (session.presetId != null && !session.hydrationPending && !session.savePending) {
                        masqueImports.rebindPendingSession(session.sessionId, editorRecoverySessionId.value)
                    }
                }
            }
            observeConfigEditorDraftRecovery(
                scope = viewModelScope,
                editorSession = editorSession,
                recoverySessionId = editorRecoverySessionId,
                recoveryReady = editorRecoveryReady,
                persistenceError = editorRecoveryPersistenceError,
                storeLock = editorRecoveryStoreLock,
                store = editorDraftStore,
                log = log,
            )
            restoreEditorDraft()
        }

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
                startConfigRuntimeMode(mode, serviceController, stringResolver, _effects)
            } else {
                stopConfigRuntimeMode(mode, serviceStateStore, serviceController)
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
                            if (editorSession.value == expectedSession) {
                                rotateEditorRecoverySessionAndClearMasqueState {
                                    editorSession.compareAndSet(expectedSession, ConfigEditorSession())
                                }
                            }
                        }
                    }
                }
            }
        }

        fun startEditingPreset(presetId: String = "custom") {
            val saveJob = activeSaveJob.get()
            when {
                saveJob != null -> {
                    suppressActiveConfigSaveSuccess(editorSession, activeSaveRequest)
                    viewModelScope.launch {
                        saveJob.join()
                        startEditingPreset(presetId)
                    }
                }

                editorRecoveryPending.value -> {
                    pendingEditorStartPresetId = presetId
                }

                editorRecoveryRestoreFailed -> {
                    pendingEditorStartPresetId = presetId
                    restoreEditorDraft()
                }

                else -> {
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
                            val hydrated =
                                session.completeHydration(sessionId, hydration.getOrThrow())
                            if (editorSession.compareAndSet(
                                    expect = session,
                                    update = hydrated,
                                )
                            ) {
                                masqueImports.rebindPendingSession(sessionId, editorRecoverySessionId.value)
                            }
                        } else if (error is CancellationException) {
                            editorSession.compareAndSet(session, ConfigEditorSession())
                            throw error
                        } else if (editorSession.value == session) {
                            log.e(error) { "Failed to hydrate mode editor relay artifacts" }
                            _effects.emit(ConfigEffect.EditorHydrationFailed(sessionId))
                        }
                    }
                }
            }
        }

        fun updateDraft(
            expectedSessionId: Long? = null,
            transform: ConfigDraft.() -> ConfigDraft,
        ): Boolean {
            if (expectedSessionId != null) {
                return editorSession.updateDraftForSession(expectedSessionId, transform)
            }
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
            return true
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

        fun updateRelayMasqueCertificateChain(value: String) {
            synchronized(editorOperationLock) {
                masqueImportGenerations.invalidateCertificate()
                updateDraft { copy(relayMasqueClientCertificateChainPem = value) }
            }
        }

        fun updateRelayMasquePrivateKey(value: String) {
            synchronized(editorOperationLock) {
                masqueImportGenerations.invalidatePrivateKey()
                updateDraft { copy(relayMasqueClientPrivateKeyPem = value) }
            }
        }

        suspend fun cancelEditing(): Boolean {
            val canCancel =
                synchronized(editorOperationLock) {
                    if (
                        editorRecoveryPending.value ||
                        activeSaveRequest.value != null ||
                        activeSaveJob.get() != null
                    ) {
                        false
                    } else {
                        true
                    }
                }
            val expectedSession = editorSession.value
            return canCancel &&
                rotateEditorRecoverySessionAndClearMasqueState {
                    editorSession.compareAndSet(expectedSession, ConfigEditorSession())
                }
        }

        internal suspend fun requestEditorExit(): ConfigEditorExitDecision {
            val decision =
                synchronized(editorOperationLock) {
                    when {
                        editorRecoveryPending.value -> {
                            ConfigEditorExitDecision.Blocked
                        }

                        activeSaveRequest.value != null || activeSaveJob.get() != null -> {
                            ConfigEditorExitDecision.Blocked
                        }

                        editorSession.value.isDirty -> {
                            ConfigEditorExitDecision.ConfirmDiscard
                        }

                        else -> {
                            ConfigEditorExitDecision.Exit
                        }
                    }
                }
            if (decision != ConfigEditorExitDecision.Exit) return decision
            val expectedSession = editorSession.value
            return if (
                rotateEditorRecoverySessionAndClearMasqueState {
                    editorSession.compareAndSet(expectedSession, ConfigEditorSession())
                }
            ) {
                ConfigEditorExitDecision.Exit
            } else {
                ConfigEditorExitDecision.Blocked
            }
        }

        internal val currentEditorSessionId: Long?
            get() =
                editorSession.value
                    .takeIf { current -> current.presetId != null && !current.hydrationPending }
                    ?.sessionId

        internal val currentEditorRecoveryOwnerId: String
            get() = editorRecoverySessionId.value

        fun importRelayMasqueCertificateChain(
            uri: Uri,
            expectedSessionId: Long,
        ) = masqueCredentialImportRunner.importCertificateChain(uri, expectedSessionId)

        fun importRelayMasquePrivateKey(
            uri: Uri,
            expectedSessionId: Long,
        ) = masqueCredentialImportRunner.importPrivateKey(uri, expectedSessionId)

        fun importRelayMasquePkcs12(
            uri: Uri,
            password: String?,
            expectedSessionId: Long,
        ) = masqueCredentialImportRunner.importPkcs12(uri, password, expectedSessionId)

        fun saveDraft() {
            val pendingSave =
                synchronized(editorOperationLock) {
                    if (masqueImportSessionCoordinator.hasInFlightImport(editorSession.value.sessionId)) {
                        null
                    } else {
                        beginConfigSave(
                            editorSession = editorSession,
                            activeSaveRequest = activeSaveRequest,
                            fallbackDraft = uiState.value.draft,
                        )?.let { request ->
                            val job =
                                viewModelScope.launch(start = CoroutineStart.LAZY) {
                                    saveDraft(request)
                                }
                            if (activeSaveJob.compareAndSet(null, job)) {
                                job to request
                            } else {
                                activeSaveRequest.compareAndSet(request, null)
                                null
                            }
                        }
                    }
                } ?: return
            val (saveJob, request) = pendingSave
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
                                applySavedConfigDraftToRunningService(
                                    draft = persistedDraft,
                                    serviceStateStore = serviceStateStore,
                                    serviceController = serviceController,
                                    startRuntimeMode = { mode ->
                                        startConfigRuntimeMode(mode, serviceController, stringResolver, _effects)
                                    },
                                )
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
                        finishSuccessfulConfigSave(
                            editorSession = editorSession,
                            request = request,
                            rotateRecoverySession = rotateEditorRecoverySessionAndClearMasqueState,
                            notifySuccess = { _effects.emit(ConfigEffect.SaveSuccess) },
                        )
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
                val expectedSession = editorSession.value
                val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
                appSettingsRepository.update {
                    applyConfigDraft(defaultDraft)
                }
                if (editorSession.value == expectedSession) {
                    rotateEditorRecoverySessionAndClearMasqueState {
                        editorSession.compareAndSet(expectedSession, ConfigEditorSession())
                    }
                }
            }
        }

        private fun restoreEditorDraft() =
            restoreConfigEditorDraftSession(
                scope = viewModelScope,
                recoveryPending = editorRecoveryPending,
                recoveryReady = editorRecoveryReady,
                recoverySessionId = editorRecoverySessionId,
                storeLock = editorRecoveryStoreLock,
                store = editorDraftStore,
                invalidatedSessionIds = invalidatedEditorRecoverySessionIds,
                nextEditorSessionId = editorSessionIds::incrementAndGet,
                onRestored = { session ->
                    editorSession.value = session
                    masqueImports.rebindPendingSession(session.sessionId, editorRecoverySessionId.value)
                    editorRecoveryRestoreFailed = false
                    pendingEditorStartPresetId = null
                },
                onMissing = {
                    val next = newConfigEditorRecoverySessionId()
                    updateEditorRecoverySessionId(next)
                    editorRecoverySessionId.value = next
                    editorRecoveryRestoreFailed = false
                    val pendingPresetId = pendingEditorStartPresetId
                    pendingEditorStartPresetId = null
                    pendingPresetId?.let(::startEditingPreset)
                },
                onFailure = { cause, sessionId ->
                    editorRecoveryRestoreFailed = true
                    editorSession.value =
                        ConfigEditorSession(
                            sessionId = sessionId,
                            presetId = pendingEditorStartPresetId ?: "custom",
                            hydrationPending = true,
                        )
                    pendingEditorStartPresetId = null
                    log.e(cause) { "Failed to restore mode editor recovery" }
                    _effects.emit(ConfigEffect.EditorHydrationFailed(sessionId))
                },
            )
    }

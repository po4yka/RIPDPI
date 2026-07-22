package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.config.relay.resolveRelayPresetSuggestion
import com.poyka.ripdpi.config.relay.toUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.BufferOverflow
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
        private val editorSession = MutableStateFlow(ConfigEditorSession())
        internal val masqueImports =
            ConfigMasqueImportController(
                importCertificateChain = ::importRelayMasqueCertificateChain,
                importPrivateKey = ::importRelayMasquePrivateKey,
                importPkcs12 = ::importRelayMasquePkcs12,
            )
        internal val masqueImportState = masqueImports.state
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
                editorSession,
                serviceStateStore.telemetry,
                runtimeSnapshots,
            ) { persistedSnapshot, session, serviceTelemetry, runtimeSnapshot ->
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
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConfigUiState(isLoading = true),
            )

        fun selectMode(mode: Mode) {
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
            val preset = uiState.value.presets.firstOrNull { it.id == presetId } ?: return
            if (preset.kind == ConfigPresetKind.Custom) {
                startEditingPreset(presetId)
                return
            }

            viewModelScope.launch {
                appSettingsRepository.update {
                    applyConfigDraft(preset.draft)
                }
                editorSession.value = ConfigEditorSession()
            }
        }

        fun startEditingPreset(presetId: String = "custom") {
            val preset = uiState.value.presets.firstOrNull { it.id == presetId }
            val draft = preset?.draft ?: uiState.value.draft
            editorSession.value =
                ConfigEditorSession(
                    presetId = presetId,
                    draft = draft,
                )
            viewModelScope.launch {
                val hydratedDraft = relayArtifacts.hydrate(draft)
                editorSession.update { current ->
                    if (current.presetId != presetId) {
                        current
                    } else {
                        current.copy(draft = hydratedDraft)
                    }
                }
            }
        }

        fun updateDraft(transform: ConfigDraft.() -> ConfigDraft) {
            editorSession.update { current ->
                val baseDraft = current.draft ?: uiState.value.draft
                current.copy(draft = baseDraft.transform())
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

        fun cancelEditing() {
            editorSession.value = ConfigEditorSession()
            clearMasqueImportState()
        }

        fun importRelayMasqueCertificateChain(uri: Uri) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importCertificateChainPem(uri) }
                    .onSuccess { certificateChain ->
                        updateDraft { copy(relayMasqueClientCertificateChainPem = certificateChain) }
                    }.onFailure { error ->
                        _effects.emit(
                            ConfigEffect.Message(
                                error.message ?: stringResolver.getString(R.string.config_import_certificate_failed),
                            ),
                        )
                    }
            }
        }

        fun importRelayMasquePrivateKey(uri: Uri) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importPrivateKeyPem(uri) }
                    .onSuccess { privateKey ->
                        updateDraft { copy(relayMasqueClientPrivateKeyPem = privateKey) }
                    }.onFailure { error ->
                        _effects.emit(
                            ConfigEffect.Message(
                                error.message ?: stringResolver.getString(R.string.config_import_private_key_failed),
                            ),
                        )
                    }
            }
        }

        fun importRelayMasquePkcs12(
            uri: Uri,
            password: String?,
        ) {
            viewModelScope.launch {
                runCatching { masqueClientCredentialImporter.importPkcs12Identity(uri, password) }
                    .onSuccess(::applyImportedMasqueIdentity)
                    .onFailure { error ->
                        _effects.emit(
                            ConfigEffect.Message(
                                error.message ?: stringResolver.getString(R.string.config_import_pkcs12_failed),
                            ),
                        )
                    }
            }
        }

        fun saveDraft() {
            val draft = editorSession.value.draft ?: uiState.value.draft
            viewModelScope.launch {
                val relayProfileRecords = relayArtifacts.listProfiles()
                if (
                    validateConfigDraft(
                        draft = draft,
                        supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                        relayProfiles = relayProfileRecords,
                    ).isNotEmpty()
                ) {
                    _effects.emit(ConfigEffect.ValidationFailed)
                    return@launch
                }
                val persistedDraft =
                    relayArtifacts.prepareForPersistence(draft)
                relayArtifacts.persist(persistedDraft)
                applySavedDraftToRunningService(persistedDraft)
                editorSession.value = ConfigEditorSession()
                clearMasqueImportState()
                _effects.emit(ConfigEffect.SaveSuccess)
            }
        }

        fun resetToDefaults() {
            viewModelScope.launch {
                val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
                appSettingsRepository.update {
                    applyConfigDraft(defaultDraft)
                }
                editorSession.value = ConfigEditorSession()
            }
        }

        private fun applyImportedMasqueIdentity(identity: ImportedMasqueClientIdentity) {
            updateDraft {
                copy(
                    relayMasqueClientCertificateChainPem = identity.certificateChainPem,
                    relayMasqueClientPrivateKeyPem = identity.privateKeyPem,
                )
            }
        }

        private fun clearMasqueImportState() {
            masqueImports.clear()
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

private fun Mode.startSenderName(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            Mode.VPN -> R.string.home_mode_vpn
            Mode.Proxy -> R.string.home_mode_proxy
        },
    )

package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.config.relay.resolveRelayPresetSuggestion
import com.poyka.ripdpi.config.relay.toUiState
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.screens.diagnostics.state.recommendTransportRemediation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException

internal class ConnectionUiStateFactory(
    private val dependencies: ConnectionViewModelDependencies,
    private val supportsMasquePrivacyPass: Boolean,
    private val masquePrivacyPassBuildStatus: MasquePrivacyPassBuildStatus,
) {
    fun create(
        settings: AppSettings,
        editorState: ConnectionEditorState,
        serviceStatus: Pair<AppStatus, Mode>,
        serviceTelemetry: ServiceTelemetrySnapshot,
        latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
        relayPresets: List<RelayPresetDefinition>,
        relayProfileRecords: List<RelayProfileRecord>,
        networkSnapshot: NativeNetworkSnapshot?,
        capabilityRecords: List<ServerCapabilityRecord>,
    ): ConnectionUiState {
        val session = editorState.session
        val currentDraft = settings.toConfigDraft().sanitizedMasqueAuthMode()
        val draft = (session.draft ?: currentDraft).sanitizedMasqueAuthMode()
        val presets = buildConfigPresets(currentDraft)
        val relayProfiles =
            buildRelayProfileOptions(
                records = relayProfileRecords,
                chainProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId },
            )
        val projection =
            ConnectionUiStateProjection(
                settings = settings,
                editorState = editorState,
                serviceStatus = serviceStatus,
                serviceTelemetry = serviceTelemetry,
                latestDirectModeOutcome = latestDirectModeOutcome,
                currentDraft = currentDraft,
                draft = draft,
                presets = presets,
                editingPreset = session.editingPreset(presets, draft),
                relayProfileRecords = relayProfileRecords,
                relayProfiles = relayProfiles,
                vpnProfiles = buildVpnProfileOptions(relayProfileRecords),
                relayPresets = relayPresets,
                networkSnapshot = networkSnapshot,
                capabilityRecords = capabilityRecords,
            )
        return buildState(projection)
    }

    private fun buildState(projection: ConnectionUiStateProjection): ConnectionUiState {
        val draft = projection.draft
        val relayPresetSuggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    dependencies.relayPresetCatalog.suggestFor(
                        projection.networkSnapshot,
                        projection.capabilityRecords,
                    ),
                serviceTelemetry = projection.serviceTelemetry,
                capabilityRecords = projection.capabilityRecords,
                transportRemediation =
                    recommendTransportRemediation(
                        result = projection.latestDirectModeOutcome?.result,
                        reasonCode = projection.latestDirectModeOutcome?.reasonCode,
                        transportClass = projection.latestDirectModeOutcome?.transportClass,
                    ),
            ).toUiState(draft)
        return ConnectionUiState(
            activeMode = projection.currentDraft.mode,
            activeRelayProfileId = projection.currentDraft.relayProfileId,
            activeRelayEnabled = projection.currentDraft.relayEnabled,
            runningMode =
                projection.serviceStatus.second.takeIf {
                    projection.serviceStatus.first == AppStatus.Running
                },
            runtimeOperation =
                ConnectionRuntimeOperation.Reconnecting.takeIf {
                    projection.serviceStatus.first == AppStatus.Reconnecting
                },
            uiPersona = projection.settings.uiPersona.ifBlank { "simple" },
            presets = projection.presets,
            editingPreset = projection.editingPreset,
            draft = draft,
            validationErrors =
                validateConfigDraft(
                    draft = draft,
                    supportsMasquePrivacyPass = supportsMasquePrivacyPass,
                    relayProfiles = projection.relayProfileRecords,
                ),
            relayProfiles = projection.relayProfiles,
            vpnProfiles = projection.vpnProfiles,
            relayChainTrustWarning = resolveRelayChainTrustWarning(draft, projection.relayProfiles),
            relayChainHopStatus = buildRelayChainHopStatus(projection.serviceTelemetry.relayTelemetry),
            relayPresets =
                projection.relayPresets
                    .map { preset ->
                        RelayPresetUiState(
                            id = preset.id,
                            title = preset.title,
                            selected = draft.relayPresetId == preset.id,
                        )
                    }.toImmutableList(),
            relayPresetSuggestion = relayPresetSuggestion,
            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
            masquePrivacyPassBuildStatus = masquePrivacyPassBuildStatus,
            isEditorDirty = projection.editorState.session.isDirty,
            isEditorLoading =
                projection.editorState.session.hydrationPending || projection.editorState.recoveryPending,
            isEditorSaving = projection.editorState.saveInFlight,
            isEditorImporting = projection.editorState.importInFlight,
            hasEditorRecoveryPersistenceError = projection.editorState.recoveryPersistenceError,
        )
    }

    private fun ConnectionDraft.sanitizedMasqueAuthMode(): ConnectionDraft =
        sanitizeMasqueAuthModeForCurrentBuild(this, supportsMasquePrivacyPass)
}

internal suspend fun loadRelayProfileRecordsOrEmpty(
    load: suspend () -> List<RelayProfileRecord>,
): List<RelayProfileRecord> =
    try {
        load()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        emptyList()
    }

private fun ConnectionEditorSession.editingPreset(
    presets: ImmutableList<ConnectionPreset>,
    draft: ConnectionDraft,
): ConnectionPreset? =
    presetId?.let { id ->
        presets.firstOrNull { it.id == id }?.copy(draft = draft)
            ?: ConnectionPreset(
                id = id,
                kind = ConnectionPresetKind.Custom,
                draft = draft,
            )
    }

private data class ConnectionUiStateProjection(
    val settings: AppSettings,
    val editorState: ConnectionEditorState,
    val serviceStatus: Pair<AppStatus, Mode>,
    val serviceTelemetry: ServiceTelemetrySnapshot,
    val latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
    val currentDraft: ConnectionDraft,
    val draft: ConnectionDraft,
    val presets: ImmutableList<ConnectionPreset>,
    val editingPreset: ConnectionPreset?,
    val relayProfileRecords: List<RelayProfileRecord>,
    val relayProfiles: ImmutableList<RelayProfileUiState>,
    val vpnProfiles: ImmutableList<RelayProfileUiState>,
    val relayPresets: List<RelayPresetDefinition>,
    val networkSnapshot: NativeNetworkSnapshot?,
    val capabilityRecords: List<ServerCapabilityRecord>,
)

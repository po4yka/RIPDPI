package com.poyka.ripdpi.activities

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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException

internal class ConfigUiStateFactory(
    private val dependencies: ConfigViewModelDependencies,
    private val supportsMasquePrivacyPass: Boolean,
    private val masquePrivacyPassBuildStatus: MasquePrivacyPassBuildStatus,
) {
    fun create(
        settings: AppSettings,
        editorState: ConfigEditorState,
        serviceStatus: Pair<AppStatus, Mode>,
        serviceTelemetry: ServiceTelemetrySnapshot,
        latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
        relayPresets: List<RelayPresetDefinition>,
        relayProfileRecords: List<RelayProfileRecord>,
        networkSnapshot: NativeNetworkSnapshot?,
        capabilityRecords: List<ServerCapabilityRecord>,
    ): ConfigUiState {
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
            ConfigUiStateProjection(
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

    private fun buildState(projection: ConfigUiStateProjection): ConfigUiState {
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
        return ConfigUiState(
            activeMode = projection.currentDraft.mode,
            runningMode =
                projection.serviceStatus.second.takeIf {
                    projection.serviceStatus.first == AppStatus.Running
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

    private fun ConfigDraft.sanitizedMasqueAuthMode(): ConfigDraft =
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

private fun ConfigEditorSession.editingPreset(
    presets: ImmutableList<ConfigPreset>,
    draft: ConfigDraft,
): ConfigPreset? =
    presetId?.let { id ->
        presets.firstOrNull { it.id == id }?.copy(draft = draft)
            ?: ConfigPreset(
                id = id,
                kind = ConfigPresetKind.Custom,
                draft = draft,
            )
    }

private data class ConfigUiStateProjection(
    val settings: AppSettings,
    val editorState: ConfigEditorState,
    val serviceStatus: Pair<AppStatus, Mode>,
    val serviceTelemetry: ServiceTelemetrySnapshot,
    val latestDirectModeOutcome: LatestDirectModeOutcomeSnapshot?,
    val currentDraft: ConfigDraft,
    val draft: ConfigDraft,
    val presets: ImmutableList<ConfigPreset>,
    val editingPreset: ConfigPreset?,
    val relayProfileRecords: List<RelayProfileRecord>,
    val relayProfiles: ImmutableList<RelayProfileUiState>,
    val vpnProfiles: ImmutableList<RelayProfileUiState>,
    val relayPresets: List<RelayPresetDefinition>,
    val networkSnapshot: NativeNetworkSnapshot?,
    val capabilityRecords: List<ServerCapabilityRecord>,
)

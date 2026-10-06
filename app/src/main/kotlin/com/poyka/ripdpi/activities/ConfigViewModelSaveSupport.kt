package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun observeConfigCapabilityEvidence(
    scope: CoroutineScope,
    appSettingsRepository: AppSettingsRepository,
    serviceStateStore: ServiceStateStore,
    capabilityObserver: ConfigCapabilityObserver,
) {
    scope.launch {
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

internal suspend fun applySavedConfigDraftToRunningService(
    draft: ConfigDraft,
    appSettingsRepository: AppSettingsRepository,
    serviceStateStore: ServiceStateStore,
    reconnectCoordinator: com.poyka.ripdpi.services.RunningServiceReconnect,
    onReconnectFailure: (com.poyka.ripdpi.services.RunningReconnectResult.Failed) -> Unit,
    onUnsupportedVpnDns: () -> Unit = {},
) {
    if (serviceStateStore.status.value.first != AppStatus.Running) return
    if (draft.mode == Mode.VPN && hasUnsupportedVpnDoq(appSettingsRepository.snapshot())) {
        onUnsupportedVpnDns()
        return
    }
    val result =
        reconnectCoordinator.reconnect(
            com.poyka.ripdpi.services.RunningReconnectRequest
                .CurrentSaved(draft.mode),
        )
    if (result is com.poyka.ripdpi.services.RunningReconnectResult.Failed) onReconnectFailure(result)
}

internal suspend fun startConfigRuntimeMode(
    mode: Mode,
    serviceController: ServiceController,
    stringResolver: StringResolver,
    effects: MutableSharedFlow<ConfigEffect>,
    receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
) {
    when (val result = serviceController.startPrepared(mode, receipt)) {
        is ServiceStartResult.Accepted -> {
            return
        }

        is ServiceStartResult.Rejected -> {
            val senderName = result.mode.startSenderName(stringResolver)
            val message =
                stringResolver.getString(R.string.failed_to_start, senderName) +
                    ": " +
                    result.reason.displayMessage(stringResolver)
            effects.tryEmit(ConfigEffect.Message(message))
        }
    }
}

internal fun stopConfigRuntimeMode(
    mode: Mode,
    serviceStateStore: ServiceStateStore,
    serviceController: ServiceController,
    receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
) {
    if (serviceStateStore.status.value == AppStatus.Running to mode) {
        serviceController.stopPrepared(receipt)
    }
}

internal data class ConfigSaveRequest(
    val sessionId: Long,
    val draftRevision: Long,
    val relayBindingRevision: Long,
    val draft: ConfigDraft,
)

internal data class ConfigEditorState(
    val session: ConfigEditorSession,
    val saveInFlight: Boolean,
    val importInFlight: Boolean,
    val recoveryPending: Boolean,
    val recoveryPersistenceError: Boolean,
)

internal data class ConfigEditorRecoverySnapshot(
    val session: ConfigEditorSession,
    val recoverySessionId: String,
    val ready: Boolean,
)

internal sealed interface ConfigSaveOutcome {
    data object ValidationFailed : ConfigSaveOutcome

    data class Saved(
        val draft: ConfigDraft,
    ) : ConfigSaveOutcome

    data object Stale : ConfigSaveOutcome
}

internal suspend fun persistConfigSaveRequest(
    request: ConfigSaveRequest,
    dependencies: ConfigViewModelDependencies,
    supportsMasquePrivacyPass: Boolean,
    editorSession: MutableStateFlow<ConfigEditorSession>,
    onReconnectFailure: (com.poyka.ripdpi.services.RunningReconnectResult.Failed) -> Unit,
    onUnsupportedVpnDns: () -> Unit,
): ConfigSaveOutcome {
    val relayArtifacts = dependencies.relayArtifacts
    val relayProfileRecords = relayArtifacts.listProfiles()
    if (
        validateConfigDraft(
            draft = request.draft,
            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
            relayProfiles = relayProfileRecords,
        ).isNotEmpty()
    ) {
        return ConfigSaveOutcome.ValidationFailed
    }
    val persistedDraft = relayArtifacts.prepareForPersistence(request.draft)
    currentCoroutineContext().ensureActive()
    return if (editorSession.value.sessionId != request.sessionId) {
        ConfigSaveOutcome.Stale
    } else {
        val savedDraft = relayArtifacts.persist(persistedDraft)
        if (persistedDraft.mode == Mode.Proxy) {
            dependencies.xrayNativeProviderSelection.selectNativeMode(
                com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit,
                persistedDraft.mode,
            )
        }
        currentCoroutineContext().ensureActive()
        if (editorSession.value.sessionId != request.sessionId) {
            ConfigSaveOutcome.Stale
        } else {
            applySavedConfigDraftToRunningService(
                draft = persistedDraft,
                appSettingsRepository = dependencies.appSettingsRepository,
                serviceStateStore = dependencies.serviceStateStore,
                reconnectCoordinator = dependencies.reconnectCoordinator,
                onReconnectFailure = onReconnectFailure,
                onUnsupportedVpnDns = onUnsupportedVpnDns,
            )
            ConfigSaveOutcome.Saved(savedDraft)
        }
    }
}

internal fun beginConfigSave(
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
                    relayBindingRevision = session.relayBindingRevision,
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

internal fun clearConfigSavePending(
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

internal fun completeSuccessfulConfigSave(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    request: ConfigSaveRequest,
    savedDraft: ConfigDraft,
): ConfigSaveCompletion {
    var result = ConfigSaveCompletion()
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
                        baselineDraft = savedDraft,
                        draft =
                            current.draft?.let { draft ->
                                if (current.relayBindingRevision == request.relayBindingRevision) {
                                    draft.withSavedRelayIdentityFrom(savedDraft)
                                } else {
                                    draft
                                }
                            },
                        savePending = false,
                    )
                }
            if (editorSession.compareAndSet(current, completed)) {
                result =
                    ConfigSaveCompletion(
                        completedCurrentRevision = requestIsCurrent,
                        shouldNotifySuccess = requestIsCurrent && !current.suppressSaveSuccess,
                    )
                complete = true
            }
        }
    }
    return result
}

internal data class ConfigSaveCompletion(
    val completedCurrentRevision: Boolean = false,
    val shouldNotifySuccess: Boolean = false,
)

internal suspend fun finishSuccessfulConfigSave(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    request: ConfigSaveRequest,
    savedDraft: ConfigDraft,
    rotateRecoverySession: suspend (() -> Boolean) -> Boolean,
    notifySuccess: suspend () -> Unit,
) {
    val current = editorSession.value
    if (
        current.sessionId == request.sessionId &&
        current.savePending &&
        current.draftRevision == request.draftRevision
    ) {
        var completion = ConfigSaveCompletion()
        val rotated =
            rotateRecoverySession {
                completion = completeSuccessfulConfigSave(editorSession, request, savedDraft)
                true
            }
        if (!rotated) {
            clearConfigSavePending(editorSession, request)
        } else if (completion.shouldNotifySuccess) {
            notifySuccess()
        }
    } else {
        completeSuccessfulConfigSave(editorSession, request, savedDraft)
    }
}

internal fun suppressActiveConfigSaveSuccess(
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

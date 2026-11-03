package com.poyka.ripdpi.ui.screens.connection

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.ui.screens.status.hasUnsupportedVpnDoq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun observeConfigCapabilityEvidence(
    scope: CoroutineScope,
    appSettingsRepository: AppSettingsRepository,
    serviceStateStore: ServiceStateStore,
    capabilityObserver: ConnectionCapabilityObserver,
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
    draft: ConnectionDraft,
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

internal data class ConnectionSaveRequest(
    val sessionId: Long,
    val draftRevision: Long,
    val relayBindingRevision: Long,
    val draft: ConnectionDraft,
)

internal data class ConnectionEditorState(
    val session: ConnectionEditorSession,
    val saveInFlight: Boolean,
    val importInFlight: Boolean,
    val recoveryPending: Boolean,
    val recoveryPersistenceError: Boolean,
)

internal data class ConnectionEditorRecoverySnapshot(
    val session: ConnectionEditorSession,
    val recoverySessionId: String,
    val ready: Boolean,
)

internal sealed interface ConnectionSaveOutcome {
    data object ValidationFailed : ConnectionSaveOutcome

    data class Saved(
        val draft: ConnectionDraft,
    ) : ConnectionSaveOutcome

    data object Stale : ConnectionSaveOutcome
}

internal suspend fun persistConfigSaveRequest(
    request: ConnectionSaveRequest,
    dependencies: ConnectionViewModelDependencies,
    supportsMasquePrivacyPass: Boolean,
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    onReconnectFailure: (com.poyka.ripdpi.services.RunningReconnectResult.Failed) -> Unit,
    onUnsupportedVpnDns: () -> Unit,
    onApplyFailure: () -> Unit,
): ConnectionSaveOutcome {
    val relayArtifacts = dependencies.relayArtifacts
    val relayProfileRecords = relayArtifacts.listProfiles()
    if (
        validateConfigDraft(
            draft = request.draft,
            supportsMasquePrivacyPass = supportsMasquePrivacyPass,
            relayProfiles = relayProfileRecords,
        ).isNotEmpty()
    ) {
        return ConnectionSaveOutcome.ValidationFailed
    }
    val persistedDraft = relayArtifacts.prepareForPersistence(request.draft)
    currentCoroutineContext().ensureActive()
    return if (editorSession.value.sessionId != request.sessionId) {
        ConnectionSaveOutcome.Stale
    } else {
        val savedDraft = relayArtifacts.persist(persistedDraft)
        applyCommittedConfigSave(
            request,
            persistedDraft,
            dependencies,
            editorSession,
            onReconnectFailure,
            onUnsupportedVpnDns,
            onApplyFailure,
        )
        currentCoroutineContext().ensureActive()
        if (editorSession.value.sessionId != request.sessionId) {
            ConnectionSaveOutcome.Stale
        } else {
            ConnectionSaveOutcome.Saved(savedDraft)
        }
    }
}

/**
 * Runs the steps after the profile and settings are committed. They run in a child job: the reconnect
 * coordinator's cancel targets the job that called reconnect, and it must not cancel the editor's save.
 * The save itself already succeeded, so a failure here keeps it saved and reports [onApplyFailure]: the
 * running provider or service may still use the previous config.
 */
private suspend fun applyCommittedConfigSave(
    request: ConnectionSaveRequest,
    persistedDraft: ConnectionDraft,
    dependencies: ConnectionViewModelDependencies,
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    onReconnectFailure: (com.poyka.ripdpi.services.RunningReconnectResult.Failed) -> Unit,
    onUnsupportedVpnDns: () -> Unit,
    onApplyFailure: () -> Unit,
) = coroutineScope {
    launch {
        try {
            if (persistedDraft.mode == Mode.Proxy) {
                dependencies.xrayNativeProviderSelection.selectNativeMode(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit,
                    persistedDraft.mode,
                )
            }
            if (editorSession.value.sessionId == request.sessionId) {
                applySavedConfigDraftToRunningService(
                    draft = persistedDraft,
                    appSettingsRepository = dependencies.appSettingsRepository,
                    serviceStateStore = dependencies.serviceStateStore,
                    reconnectCoordinator = dependencies.reconnectCoordinator,
                    onReconnectFailure = onReconnectFailure,
                    onUnsupportedVpnDns = onUnsupportedVpnDns,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            Logger.w { "Applying a saved config failed: ${ignored.javaClass.simpleName}" }
            onApplyFailure()
        }
    }.join()
}

internal fun beginConfigSave(
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    activeSaveRequest: MutableStateFlow<ConnectionSaveRequest?>,
    fallbackDraft: ConnectionDraft,
): ConnectionSaveRequest? {
    var result: ConnectionSaveRequest? = null
    var complete = false
    while (!complete) {
        val session = editorSession.value
        val request =
            if (session.hydrationPending || session.savePending || activeSaveRequest.value != null) {
                null
            } else {
                ConnectionSaveRequest(
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
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    request: ConnectionSaveRequest,
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
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    request: ConnectionSaveRequest,
    savedDraft: ConnectionDraft,
): ConnectionSaveCompletion {
    var result = ConnectionSaveCompletion()
    var complete = false
    while (!complete) {
        val current = editorSession.value
        if (current.sessionId != request.sessionId || !current.savePending) {
            complete = true
        } else {
            val requestIsCurrent = current.draftRevision == request.draftRevision
            val completed =
                if (requestIsCurrent) {
                    ConnectionEditorSession()
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
                    ConnectionSaveCompletion(
                        completedCurrentRevision = requestIsCurrent,
                        shouldNotifySuccess = requestIsCurrent && !current.suppressSaveSuccess,
                    )
                complete = true
            }
        }
    }
    return result
}

internal data class ConnectionSaveCompletion(
    val completedCurrentRevision: Boolean = false,
    val shouldNotifySuccess: Boolean = false,
)

internal suspend fun finishSuccessfulConfigSave(
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    request: ConnectionSaveRequest,
    savedDraft: ConnectionDraft,
    rotateRecoverySession: suspend (() -> Boolean) -> Boolean,
    notifySuccess: suspend () -> Unit,
) {
    val current = editorSession.value
    if (
        current.sessionId == request.sessionId &&
        current.savePending &&
        current.draftRevision == request.draftRevision
    ) {
        var completion = ConnectionSaveCompletion()
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
    editorSession: MutableStateFlow<ConnectionEditorSession>,
    activeSaveRequest: MutableStateFlow<ConnectionSaveRequest?>,
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

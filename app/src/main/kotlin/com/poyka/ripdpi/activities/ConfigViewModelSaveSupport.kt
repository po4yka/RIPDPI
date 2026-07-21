package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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
    serviceStateStore: ServiceStateStore,
    serviceController: ServiceController,
    startRuntimeMode: (Mode) -> Unit,
) {
    if (serviceStateStore.status.value.first != AppStatus.Running) return
    serviceController.stop()
    val halted =
        withTimeoutOrNull(10.seconds) {
            serviceStateStore.status.first { it.first == AppStatus.Halted }
            true
        } == true
    if (halted) startRuntimeMode(draft.mode)
}

internal data class ConfigSaveRequest(
    val sessionId: Long,
    val draftRevision: Long,
    val draft: ConfigDraft,
)

internal data class ConfigEditorState(
    val session: ConfigEditorSession,
    val saveInFlight: Boolean,
    val importInFlight: Boolean,
    val recoveryPending: Boolean,
)

internal data class ConfigEditorRecoverySnapshot(
    val session: ConfigEditorSession,
    val recoverySessionId: String,
    val ready: Boolean,
)

internal enum class ConfigSaveOutcome {
    ValidationFailed,
    Saved,
    Stale,
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
                        baselineDraft = request.draft,
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

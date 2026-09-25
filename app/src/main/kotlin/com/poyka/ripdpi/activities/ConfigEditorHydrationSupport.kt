package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun launchConfigEditorHydration(
    scope: CoroutineScope,
    session: ConfigEditorSession,
    editorSession: MutableStateFlow<ConfigEditorSession>,
    relayArtifacts: ConfigRelayArtifactRepository,
    masqueImports: ConfigMasqueImportController,
    editorRecoverySessionId: MutableStateFlow<String>,
    log: Logger,
    effects: MutableSharedFlow<ConfigEffect>,
): Job =
    scope.launch {
        val sessionId = session.sessionId
        val presetId = requireNotNull(session.presetId)
        val draft = requireNotNull(session.draft)
        val hydration =
            runCatching {
                if (presetId.startsWith("relay-profile:")) {
                    relayArtifacts.hydrateProfile(presetId.removePrefix("relay-profile:"))
                } else {
                    relayArtifacts.hydrate(draft)
                }
            }
        val error = hydration.exceptionOrNull()
        if (error == null) {
            val hydrated = session.completeHydration(sessionId, hydration.getOrThrow())
            if (editorSession.compareAndSet(expect = session, update = hydrated)) {
                masqueImports.rebindPendingSession(sessionId, editorRecoverySessionId.value)
            }
        } else if (error is CancellationException) {
            editorSession.compareAndSet(session, ConfigEditorSession())
            throw error
        } else if (editorSession.value == session) {
            log.e(error) { "Failed to hydrate mode editor relay artifacts" }
            effects.emit(ConfigEffect.EditorHydrationFailed(sessionId))
        }
    }

package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val ConfigEditorRecoveryPersistenceRetryDelayMillis = 1_000L

internal fun observeConfigEditorDraftRecovery(
    scope: CoroutineScope,
    editorSession: MutableStateFlow<ConfigEditorSession>,
    recoverySessionId: MutableStateFlow<String>,
    recoveryReady: MutableStateFlow<Boolean>,
    persistenceError: MutableStateFlow<Boolean>,
    storeLock: Mutex,
    store: ConfigEditorDraftStore,
    log: Logger,
) {
    scope.launch {
        combine(
            editorSession,
            recoverySessionId,
            recoveryReady,
        ) { session, sessionId, ready ->
            ConfigEditorRecoverySnapshot(session, sessionId, ready)
        }.collectLatest { snapshot ->
            if (!snapshot.ready) return@collectLatest
            while (true) {
                val failure =
                    runCatching {
                        storeLock.withLock {
                            if (snapshot.session.isDirty) {
                                store.persist(snapshot.recoverySessionId, snapshot.session)
                            } else {
                                store.delete(snapshot.recoverySessionId)
                            }
                        }
                    }.exceptionOrNull()
                when (failure) {
                    null -> {
                        persistenceError.value = false
                        return@collectLatest
                    }

                    is CancellationException -> {
                        throw failure
                    }

                    else -> {
                        log.e(failure) { "Failed to update mode editor recovery" }
                        if (snapshot.session.isDirty) persistenceError.value = true
                        delay(ConfigEditorRecoveryPersistenceRetryDelayMillis)
                    }
                }
            }
        }
    }
}

internal suspend fun restoreConfigEditorDraftRecovery(
    storeLock: Mutex,
    store: ConfigEditorDraftStore,
    invalidatedSessionIds: Collection<String>,
    recoverySessionId: String,
): ConfigEditorDraftRestoreResult =
    runCatching {
        storeLock.withLock {
            invalidatedSessionIds.forEach { invalidatedSessionId ->
                store.invalidate(invalidatedSessionId)
            }
            store.restore(recoverySessionId)
        }
    }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        ConfigEditorDraftRestoreResult.RestoreFailed(failure)
    }

internal suspend fun rotateConfigEditorRecoverySession(
    previousSessionId: String,
    recoveryPending: MutableStateFlow<Boolean>,
    recoveryReady: MutableStateFlow<Boolean>,
    persistenceError: MutableStateFlow<Boolean>,
    storeLock: Mutex,
    store: ConfigEditorDraftStore,
    transitionAfterInvalidation: () -> Boolean,
    onInvalidationFailure: suspend (Throwable) -> Unit,
    onInvalidated: (String) -> Unit,
    onRotationSucceeded: () -> Unit,
    newSessionId: () -> String,
    updateSessionId: (String) -> Unit,
): Boolean {
    recoveryPending.value = true
    recoveryReady.value = false
    val failure =
        runCatching {
            withContext(NonCancellable) {
                storeLock.withLock {
                    store.invalidate(previousSessionId)
                }
            }
        }.exceptionOrNull()
    if (failure != null) {
        if (failure is CancellationException) throw failure
        recoveryPending.value = false
        recoveryReady.value = true
        onInvalidationFailure(failure)
        return false
    }
    val transitioned = transitionAfterInvalidation()
    onInvalidated(previousSessionId)
    onRotationSucceeded()
    val next = newSessionId()
    updateSessionId(next)
    persistenceError.value = false
    recoveryReady.value = true
    recoveryPending.value = false
    return transitioned
}

package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import javax.inject.Inject

internal const val StrategyConfigSessionIdSavedStateKey = "strategy_config_session_id"

@HiltViewModel
internal class StrategyConfigEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val draftStore: StrategyConfigDraftStore,
    ) : ViewModel() {
        private val sessionId =
            savedStateHandle
                .get<String>(StrategyConfigSessionIdSavedStateKey)
                ?.takeIf(::isValidStrategyConfigSessionId)
                ?: newStrategyConfigSessionId().also { savedStateHandle[StrategyConfigSessionIdSavedStateKey] = it }
        private val persistence = StrategyConfigPersistenceCoordinator(viewModelScope, sessionId, draftStore)
        private var hydrationComplete = false
        private var hydrationInProgress = false
        private var hydrationFailed = false
        private var hydrationRetryAvailable = true
        private var hydrationRetryRequested = false
        private var pendingBuiltInConfigText: String? = null
        private var exitRequested = false
        private var discarding = false

        var session by mutableStateOf<StrategyConfigEditorSession?>(null)
            private set
        var isHydrating by mutableStateOf(true)
            private set
        var exitDecision by mutableStateOf<StrategyConfigExitDecision?>(null)
            private set

        init {
            startHydration()
        }

        fun syncBuiltIn(configText: String) {
            if (discarding) return
            val bounded = configText.boundedUtf8(StrategyConfigMaxImportBytes)
            if (!hydrationComplete) {
                pendingBuiltInConfigText = bounded
                when {
                    hydrationInProgress -> {
                        hydrationRetryRequested = true
                    }

                    hydrationRetryAvailable -> {
                        hydrationRetryAvailable = false
                        startHydration()
                    }
                }
                return
            }
            session = session?.syncCleanBuiltIn(bounded) ?: StrategyConfigEditorSession.initial(bounded)
            isHydrating = false
            session?.takeUnless { it.isDirty }?.let {
                persistence.deleteBestEffort()
            }
            reevaluateExitRequest()
        }

        fun update(transform: StrategyConfigDraft.() -> StrategyConfigDraft) {
            if (discarding) return
            session?.update(transform)?.let(::setAndPersist)
        }

        fun selectSource(
            source: StrategyConfigSource,
            builtInConfigText: String,
        ) {
            if (discarding) return
            session
                ?.selectSource(source, builtInConfigText.boundedUtf8(StrategyConfigMaxImportBytes))
                ?.let(::setAndPersist)
        }

        fun importConfig(configText: String) {
            if (discarding) return
            session?.importConfig(configText.boundedUtf8(StrategyConfigMaxImportBytes))?.let(::setAndPersist)
        }

        fun beginSave(): StrategyConfigSaveRequest? =
            if (discarding) {
                null
            } else {
                session?.beginSave()?.let { (savingSession, request) ->
                    session = savingSession
                    request
                }
            }

        fun requestExit() {
            if (exitRequested || exitDecision != null) return
            exitRequested = true
            reevaluateExitRequest()
        }

        fun consumeExitDecision(decision: StrategyConfigExitDecision) {
            if (exitDecision == decision) exitDecision = null
        }

        suspend fun completeSave(
            request: StrategyConfigSaveRequest,
            succeeded: Boolean,
        ) {
            if (discarding) return
            if (!succeeded) {
                finishSave(request, succeeded = false)
                return
            }
            var recoveryDeleted = false
            try {
                // Keep the submitted baseline dirty until removal of its recovery record is durable.
                persistence.deleteAndAwait()
                recoveryDeleted = true
            } finally {
                if (!recoveryDeleted) finishSave(request, succeeded = false)
            }
            finishSuccessfulSave(request)
        }

        suspend fun discard() {
            check(session?.isSaving != true) { "Cannot discard while a save is active" }
            discarding = true
            var deleted = false
            try {
                persistence.deleteAndAwait()
                deleted = true
            } finally {
                if (!deleted) discarding = false
            }
        }

        suspend fun runSave(
            request: StrategyConfigSaveRequest,
            save: suspend () -> StrategyConfigBanner,
        ): StrategyConfigBanner {
            var saved = false
            return try {
                save().also { saved = it.saved }
            } finally {
                completeSave(request, saved)
            }
        }

        private fun setAndPersist(value: StrategyConfigEditorSession) {
            session = value
            persistence.persistOrDelete(value)
        }

        private fun finishSave(
            request: StrategyConfigSaveRequest,
            succeeded: Boolean,
        ) {
            val completed = session?.completeSave(request, succeeded) ?: return
            session = completed
            if (completed.isDirty) {
                persistence.persistOrDelete(completed)
            }
            reevaluateExitRequest()
        }

        private suspend fun finishSuccessfulSave(request: StrategyConfigSaveRequest) {
            var finished = false
            while (!finished) {
                val current = session
                if (current == null) {
                    finished = true
                } else {
                    val completed = current.completeSave(request, succeeded = true)
                    if (!completed.isDirty) {
                        session = completed
                        reevaluateExitRequest()
                        finished = true
                    } else if (persistSuccessfulDirtySave(request, completed) && session == current) {
                        session = completed
                        reevaluateExitRequest()
                        finished = true
                    }
                }
            }
        }

        private suspend fun persistSuccessfulDirtySave(
            request: StrategyConfigSaveRequest,
            completed: StrategyConfigEditorSession,
        ): Boolean {
            var persisted = false
            try {
                persistence.persistAndAwait(completed)
                persisted = true
            } finally {
                if (!persisted) finishSave(request, succeeded = false)
            }
            return persisted
        }

        private fun reevaluateExitRequest() {
            val current = session
            val hydrationBlocksExit = isHydrating && !hydrationFailed
            if (!exitRequested || exitDecision != null) return
            if (hydrationBlocksExit || current?.isSaving == true) return
            exitRequested = false
            exitDecision =
                if (current?.isDirty == true) {
                    StrategyConfigExitDecision.ConfirmDiscard
                } else {
                    StrategyConfigExitDecision.NavigateBack
                }
        }

        private fun startHydration() {
            if (hydrationComplete || hydrationInProgress) return
            hydrationInProgress = true
            hydrationFailed = false
            viewModelScope.launch {
                val result = restoreStrategyConfigDraft(draftStore, sessionId)
                hydrationInProgress = false
                when (result) {
                    is StrategyConfigDraftRestoreResult.Restored -> completeHydration(result.session)
                    StrategyConfigDraftRestoreResult.MissingOrCorrupt -> completeHydration(null)
                    is StrategyConfigDraftRestoreResult.RestoreFailed -> handleHydrationFailure()
                }
            }
        }

        private fun completeHydration(restored: StrategyConfigEditorSession?) {
            session = restored
            hydrationComplete = true
            hydrationFailed = false
            hydrationRetryRequested = false
            pendingBuiltInConfigText?.also { pendingBuiltInConfigText = null }?.let(::syncBuiltIn)
        }

        private fun handleHydrationFailure() {
            hydrationFailed = true
            if (hydrationRetryRequested && hydrationRetryAvailable) {
                hydrationRetryRequested = false
                hydrationRetryAvailable = false
                startHydration()
            } else {
                reevaluateExitRequest()
            }
        }
    }

private suspend fun restoreStrategyConfigDraft(
    draftStore: StrategyConfigDraftStore,
    sessionId: String,
): StrategyConfigDraftRestoreResult =
    runCatching { draftStore.restore(sessionId) }
        .fold(
            onSuccess = { it },
            onFailure = { failure ->
                when (failure) {
                    is CancellationException -> throw failure
                    is Exception -> StrategyConfigDraftRestoreResult.RestoreFailed(failure)
                    else -> throw failure
                }
            },
        )

private class StrategyConfigPersistenceCoordinator(
    scope: CoroutineScope,
    private val sessionId: String,
    private val draftStore: StrategyConfigDraftStore,
) {
    private val persistenceCommands = Channel<StrategyConfigPersistenceCommand>(Channel.CONFLATED)
    private val acknowledgedCommands = Channel<StrategyConfigAcknowledgedCommand>(Channel.RENDEZVOUS)
    private var nextSequence = 0L
    private var acknowledgedThroughSequence = 0L

    init {
        scope.launch {
            while (true) {
                select {
                    acknowledgedCommands.onReceive { command -> executeAcknowledged(command) }
                    persistenceCommands.onReceive { command -> executeBestEffort(command) }
                }
            }
        }
    }

    fun persistOrDelete(value: StrategyConfigEditorSession) {
        persistenceCommands.trySend(
            if (value.isDirty) {
                StrategyConfigPersistenceCommand.Persist(newSequence(), value)
            } else {
                StrategyConfigPersistenceCommand.Delete(newSequence())
            },
        )
    }

    fun deleteBestEffort() {
        persistenceCommands.trySend(StrategyConfigPersistenceCommand.Delete(newSequence()))
    }

    suspend fun deleteAndAwait() {
        executeAndAwait(StrategyConfigAcknowledgedOperation.Delete)
    }

    suspend fun persistAndAwait(value: StrategyConfigEditorSession) {
        executeAndAwait(StrategyConfigAcknowledgedOperation.Persist(value))
    }

    private suspend fun executeAndAwait(operation: StrategyConfigAcknowledgedOperation) {
        val completion = CompletableDeferred<Unit>()
        acknowledgedCommands.send(
            StrategyConfigAcknowledgedCommand(
                sequence = newSequence(),
                operation = operation,
                completion = completion,
            ),
        )
        completion.await()
    }

    private suspend fun executeBestEffort(command: StrategyConfigPersistenceCommand) {
        if (command.sequence <= acknowledgedThroughSequence) return
        // Editing stays available after IO failure; the next mutation retries the latest snapshot.
        val failure =
            runCatching {
                when (command) {
                    is StrategyConfigPersistenceCommand.Persist -> draftStore.persist(sessionId, command.session)
                    is StrategyConfigPersistenceCommand.Delete -> draftStore.delete(sessionId)
                }
            }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure == null && command is StrategyConfigPersistenceCommand.Delete) {
            acknowledgedThroughSequence = maxOf(acknowledgedThroughSequence, command.sequence)
        }
    }

    private suspend fun executeAcknowledged(command: StrategyConfigAcknowledgedCommand) {
        val failure =
            runCatching {
                when (val operation = command.operation) {
                    StrategyConfigAcknowledgedOperation.Delete -> draftStore.delete(sessionId)
                    is StrategyConfigAcknowledgedOperation.Persist -> draftStore.persist(sessionId, operation.session)
                }
            }.exceptionOrNull()
        when (failure) {
            null -> {
                acknowledgedThroughSequence = maxOf(acknowledgedThroughSequence, command.sequence)
                command.completion.complete(Unit)
            }

            is CancellationException -> {
                command.completion.cancel(failure)
                throw failure
            }

            else -> {
                command.completion.completeExceptionally(failure)
            }
        }
    }

    private fun newSequence(): Long = ++nextSequence
}

internal enum class StrategyConfigExitDecision {
    ConfirmDiscard,
    NavigateBack,
}

private sealed interface StrategyConfigPersistenceCommand {
    val sequence: Long

    data class Persist(
        override val sequence: Long,
        val session: StrategyConfigEditorSession,
    ) : StrategyConfigPersistenceCommand

    data class Delete(
        override val sequence: Long,
    ) : StrategyConfigPersistenceCommand
}

private data class StrategyConfigAcknowledgedCommand(
    val sequence: Long,
    val operation: StrategyConfigAcknowledgedOperation,
    val completion: CompletableDeferred<Unit>,
)

private sealed interface StrategyConfigAcknowledgedOperation {
    data object Delete : StrategyConfigAcknowledgedOperation

    data class Persist(
        val session: StrategyConfigEditorSession,
    ) : StrategyConfigAcknowledgedOperation
}

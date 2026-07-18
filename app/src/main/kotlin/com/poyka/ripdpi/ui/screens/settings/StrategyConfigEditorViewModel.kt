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
        private val persistenceCommands = Channel<StrategyConfigPersistenceCommand>(Channel.CONFLATED)
        private val acknowledgedCommands = Channel<StrategyConfigAcknowledgedCommand>(Channel.RENDEZVOUS)
        private var hydrationComplete = false
        private var pendingBuiltInConfigText: String? = null
        private var exitRequested = false
        private var discarding = false
        private var nextPersistenceSequence = 0L
        private var acknowledgedThroughSequence = 0L

        var session by mutableStateOf<StrategyConfigEditorSession?>(null)
            private set
        var isHydrating by mutableStateOf(true)
            private set
        var exitDecision by mutableStateOf<StrategyConfigExitDecision?>(null)
            private set

        init {
            viewModelScope.launch {
                while (true) {
                    select {
                        acknowledgedCommands.onReceive { command ->
                            executeAcknowledged(command)
                        }
                        persistenceCommands.onReceive { command ->
                            executeBestEffort(command)
                        }
                    }
                }
            }
            viewModelScope.launch {
                val restored =
                    try {
                        draftStore.restore(sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                session = restored
                hydrationComplete = true
                pendingBuiltInConfigText?.let(::syncBuiltIn)
            }
        }

        fun syncBuiltIn(configText: String) {
            if (discarding) return
            val bounded = configText.boundedUtf8(StrategyConfigMaxImportBytes)
            if (!hydrationComplete) {
                pendingBuiltInConfigText = bounded
                return
            }
            session = session?.syncCleanBuiltIn(bounded) ?: StrategyConfigEditorSession.initial(bounded)
            isHydrating = false
            session?.takeUnless { it.isDirty }?.let {
                persistenceCommands.trySend(StrategyConfigPersistenceCommand.Delete(newPersistenceSequence()))
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
            try {
                // Keep the submitted baseline dirty until removal of its recovery record is durable.
                deleteAndAwait()
            } catch (failure: Throwable) {
                finishSave(request, succeeded = false)
                throw failure
            }
            finishSuccessfulSave(request)
        }

        suspend fun discard() {
            check(session?.isSaving != true) { "Cannot discard while a save is active" }
            discarding = true
            var deleted = false
            try {
                deleteAndAwait()
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
            persistOrDelete(value)
        }

        private fun finishSave(
            request: StrategyConfigSaveRequest,
            succeeded: Boolean,
        ) {
            val completed = session?.completeSave(request, succeeded) ?: return
            session = completed
            if (completed.isDirty) {
                persistenceCommands.trySend(
                    StrategyConfigPersistenceCommand.Persist(newPersistenceSequence(), completed),
                )
            }
            reevaluateExitRequest()
        }

        private suspend fun finishSuccessfulSave(request: StrategyConfigSaveRequest) {
            while (true) {
                val current = session ?: return
                val completed = current.completeSave(request, succeeded = true)
                if (!completed.isDirty) {
                    session = completed
                    reevaluateExitRequest()
                    return
                }
                try {
                    persistAndAwait(completed)
                } catch (failure: Throwable) {
                    finishSave(request, succeeded = false)
                    throw failure
                }
                if (session == current) {
                    session = completed
                    reevaluateExitRequest()
                    return
                }
            }
        }

        private fun reevaluateExitRequest() {
            val current = session
            if (!exitRequested || exitDecision != null || isHydrating || current?.isSaving == true) return
            exitRequested = false
            exitDecision =
                if (current?.isDirty == true) {
                    StrategyConfigExitDecision.ConfirmDiscard
                } else {
                    StrategyConfigExitDecision.NavigateBack
                }
        }

        private fun persistOrDelete(value: StrategyConfigEditorSession) {
            persistenceCommands.trySend(
                if (value.isDirty) {
                    StrategyConfigPersistenceCommand.Persist(newPersistenceSequence(), value)
                } else {
                    StrategyConfigPersistenceCommand.Delete(newPersistenceSequence())
                },
            )
        }

        private suspend fun deleteAndAwait() {
            executeAndAwait(StrategyConfigAcknowledgedOperation.Delete)
        }

        private suspend fun persistAndAwait(value: StrategyConfigEditorSession) {
            executeAndAwait(StrategyConfigAcknowledgedOperation.Persist(value))
        }

        private suspend fun executeAndAwait(operation: StrategyConfigAcknowledgedOperation) {
            val completion = CompletableDeferred<Unit>()
            acknowledgedCommands.send(
                StrategyConfigAcknowledgedCommand(
                    sequence = newPersistenceSequence(),
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
            if (failure is CancellationException) {
                throw failure
            }
            if (failure == null && command is StrategyConfigPersistenceCommand.Delete) {
                acknowledgedThroughSequence = maxOf(acknowledgedThroughSequence, command.sequence)
            }
        }

        private suspend fun executeAcknowledged(command: StrategyConfigAcknowledgedCommand) {
            val failure =
                runCatching {
                    when (val operation = command.operation) {
                        StrategyConfigAcknowledgedOperation.Delete -> {
                            draftStore.delete(sessionId)
                        }

                        is StrategyConfigAcknowledgedOperation.Persist -> {
                            draftStore.persist(
                                sessionId,
                                operation.session,
                            )
                        }
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

        private fun newPersistenceSequence(): Long = ++nextPersistenceSequence
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

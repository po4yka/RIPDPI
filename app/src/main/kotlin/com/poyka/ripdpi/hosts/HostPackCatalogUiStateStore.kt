package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.HostPackRefreshFailureCodeUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostPackCatalogUiStateStore
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow(HostPackCatalogUiState())

        val state: StateFlow<HostPackCatalogUiState> = mutableState.asStateFlow()

        fun update(state: HostPackCatalogUiState) {
            mutableState.value = state
        }
    }

internal sealed interface HostPackCatalogRefreshResult {
    data class Success(
        val state: HostPackCatalogUiState,
    ) : HostPackCatalogRefreshResult

    data class Failure(
        val state: HostPackCatalogUiState,
        val code: HostPackRefreshFailureCodeUiModel,
        val error: Throwable,
    ) : HostPackCatalogRefreshResult
}

@Singleton
class HostPackCatalogUiStateCoordinator
    @Inject
    constructor(
        private val repository: HostPackCatalogRepository,
        private val clock: HostPackCatalogClock,
        private val stateStore: HostPackCatalogUiStateStore,
    ) {
        private val initialized = AtomicBoolean(false)
        private val initializationMutex = Mutex()

        suspend fun ensureLoaded() {
            if (initialized.get()) {
                return
            }
            initializationMutex.withLock {
                if (initialized.get()) {
                    return
                }
                val loadResult = repository.loadSnapshot()
                stateStore.update(
                    HostPackCatalogUiState(
                        snapshot = loadResult.snapshot,
                        cacheDegradationCode = loadResult.cacheDegradation?.code,
                        cacheDegradationDetail = loadResult.cacheDegradation?.detail,
                    ),
                )
                initialized.set(true)
            }
        }

        internal suspend fun refresh(): HostPackCatalogRefreshResult {
            ensureLoaded()
            val previousState = stateStore.state.value
            val attemptedAt = clock.nowEpochMillis()
            stateStore.update(
                previousState.copy(
                    isRefreshing = true,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                ),
            )

            return runCatching {
                repository.refreshSnapshot()
            }.fold(
                onSuccess = { snapshot ->
                    val refreshedState =
                        HostPackCatalogUiState(
                            snapshot = snapshot,
                            isRefreshing = false,
                            lastRefreshAttemptAtEpochMillis = attemptedAt,
                        )
                    stateStore.update(refreshedState)
                    HostPackCatalogRefreshResult.Success(refreshedState)
                },
                onFailure = { error ->
                    val failedState =
                        previousState.copy(
                            isRefreshing = false,
                            lastRefreshAttemptAtEpochMillis = attemptedAt,
                            lastRefreshFailureCode = error.toRefreshFailureCode(),
                            lastRefreshFailureMessage = error.message,
                        )
                    stateStore.update(failedState)
                    HostPackCatalogRefreshResult.Failure(
                        state = failedState,
                        code = failedState.lastRefreshFailureCode ?: HostPackRefreshFailureCodeUiModel.DownloadFailed,
                        error = error,
                    )
                },
            )
        }
    }

private fun Throwable.toRefreshFailureCode(): HostPackRefreshFailureCodeUiModel =
    when (this) {
        is HostPackChecksumMismatchException,
        is HostPackChecksumFormatException,
        -> {
            HostPackRefreshFailureCodeUiModel.VerificationFailed
        }

        is HostPackCatalogParseException,
        is HostPackCatalogBuildException,
        -> {
            HostPackRefreshFailureCodeUiModel.InvalidSnapshot
        }

        else -> {
            HostPackRefreshFailureCodeUiModel.DownloadFailed
        }
    }

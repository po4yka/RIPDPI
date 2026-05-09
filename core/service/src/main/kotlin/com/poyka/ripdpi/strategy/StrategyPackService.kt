package com.poyka.ripdpi.strategy

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.toStrategyPackSettingsModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

interface StrategyPackService {
    fun initialize()

    suspend fun refreshNow()
}

@Singleton
class DefaultStrategyPackService
    @Inject
    constructor(
        private val repository: StrategyPackRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val stateStore: StrategyPackStateStore,
        private val clock: StrategyPackClock,
        @param:Named("strategyPackRefreshSuccessTtlMs")
        private val refreshSuccessTtlMs: Long,
        @param:Named("strategyPackRefreshInitialFailureBackoffMs")
        private val initialFailureBackoffMs: Long,
        @param:Named("strategyPackRefreshMaxFailureBackoffMs")
        private val maxFailureBackoffMs: Long,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : StrategyPackService {
        private val initialized = AtomicBoolean(false)
        private val schedulingMutex = Mutex()
        private val refreshExecutor = StrategyPackRefreshExecutor(repository)
        private val statePublisher = StrategyPackStatePublisher(stateStore, clock)
        private val settingsObserver = StrategyPackSettingsObserver(appSettingsRepository, applicationScope)
        private val refreshBackoffPolicy =
            StrategyPackRefreshBackoffPolicy(
                clock = clock,
                refreshSuccessTtlMs = refreshSuccessTtlMs,
                initialFailureBackoffMs = initialFailureBackoffMs,
                maxFailureBackoffMs = maxFailureBackoffMs,
            )

        private var settingsJob: Job? = null
        private var currentRefreshKey: StrategyPackRefreshKey? = null
        private var automaticRefreshJob: Job? = null
        private var consecutiveAutomaticFailures: Int = 0
        private var lastSuccessfulFetchAtEpochMillis: Long? = null

        override fun initialize() {
            if (!initialized.compareAndSet(false, true)) {
                return
            }

            applicationScope.launch {
                val initialSettings = appSettingsRepository.snapshot().toStrategyPackSettingsModel()
                val initialKey = StrategyPackRefreshKey(initialSettings)
                val initialLoad = repository.loadSnapshot()
                val initialSnapshot = initialLoad.snapshot

                schedulingMutex.withLock {
                    currentRefreshKey = initialKey
                    lastSuccessfulFetchAtEpochMillis = initialSnapshot.lastFetchedAtEpochMillis
                    consecutiveAutomaticFailures = 0
                }

                statePublisher.publishSelectionForSnapshot(
                    snapshot = initialSnapshot,
                    key = initialKey,
                    lastRefreshAttemptAtEpochMillis = initialSnapshot.lastFetchedAtEpochMillis,
                    lastRefreshError = null,
                    cacheDegradationCode = initialLoad.cacheDegradation?.code,
                    cacheDegradationDetail = initialLoad.cacheDegradation?.detail,
                )
                scheduleAutomaticRefresh(initialKey, StrategyPackRefreshScheduleReason.Startup)

                settingsJob = settingsObserver.observeRelevantChanges(initialKey, ::handleRelevantSettingsChange)
            }
        }

        override suspend fun refreshNow() {
            val settings = appSettingsRepository.snapshot().toStrategyPackSettingsModel()
            val key = StrategyPackRefreshKey(settings)
            val attemptedAt = clock.nowEpochMillis()
            runCatching {
                refreshExecutor.refresh(settings)
            }.onSuccess { snapshot ->
                schedulingMutex.withLock {
                    currentRefreshKey = key
                    consecutiveAutomaticFailures = 0
                    lastSuccessfulFetchAtEpochMillis = snapshot.lastFetchedAtEpochMillis
                }
                statePublisher.publishSelectionForSnapshot(
                    snapshot = snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = null,
                    lastRefreshFailureCode = null,
                    lastRejectedSequence = null,
                    cacheDegradationCode = null,
                    cacheDegradationDetail = null,
                )
                scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.ManualRefreshReseed)
            }.onFailure { error ->
                val currentState = stateStore.state.value
                statePublisher.publishSelectionForSnapshot(
                    snapshot = currentState.snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = error.message,
                    lastRefreshFailureCode = error.toFailureCode(),
                    lastRejectedSequence = error.toRejectedSequence(),
                    cacheDegradationCode = currentState.cacheDegradationCode,
                    cacheDegradationDetail = currentState.cacheDegradationDetail,
                )
                throw error
            }
        }

        private suspend fun handleRelevantSettingsChange(key: StrategyPackRefreshKey) {
            Logger.i {
                "Strategy-pack settings changed: channel=${key.channel}, policy=${key.refreshPolicy}, " +
                    "pinnedPackId=${key.pinnedPackId}, pinnedPackVersion=${key.pinnedPackVersion}"
            }
            schedulingMutex.withLock {
                currentRefreshKey = key
            }
            val runtimeState = stateStore.state.value
            statePublisher.publishSelectionForSnapshot(
                snapshot = runtimeState.snapshot,
                key = key,
                lastRefreshAttemptAtEpochMillis = runtimeState.lastRefreshAttemptAtEpochMillis,
                lastRefreshError = runtimeState.lastRefreshError,
                lastRefreshFailureCode = runtimeState.lastRefreshFailureCode,
                lastRejectedSequence = runtimeState.lastRejectedSequence,
                cacheDegradationCode = runtimeState.cacheDegradationCode,
                cacheDegradationDetail = runtimeState.cacheDegradationDetail,
            )
            scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.RelevantSettingsChanged)
        }

        private suspend fun scheduleAutomaticRefresh(
            key: StrategyPackRefreshKey,
            reason: StrategyPackRefreshScheduleReason,
        ) {
            val schedulePlan =
                schedulingMutex.withLock {
                    currentRefreshKey = key
                    if (!key.isAutomatic) {
                        cancelAutomaticRefreshLocked(because = "policy became ${key.refreshPolicy}")
                        return
                    }

                    val currentJob = currentCoroutineContext()[Job]
                    if (automaticRefreshJob != null && automaticRefreshJob !== currentJob) {
                        cancelAutomaticRefreshLocked(because = "refresh key changed")
                    }

                    StrategyPackSchedulePlan(
                        delayMs =
                            refreshBackoffPolicy.nextDelay(
                                reason = reason,
                                lastSuccessfulFetchAtEpochMillis = lastSuccessfulFetchAtEpochMillis,
                                consecutiveAutomaticFailures = consecutiveAutomaticFailures,
                            ),
                        reason = reason,
                    )
                }

            if (schedulePlan.reason == StrategyPackRefreshScheduleReason.Startup && schedulePlan.delayMs > 0L) {
                Logger.i {
                    "Automatic strategy-pack refresh skipped because cached snapshot is still fresh; " +
                        "rescheduling in ${schedulePlan.delayMs} ms"
                }
            }
            Logger.i {
                "Automatic strategy-pack refresh scheduled in ${schedulePlan.delayMs} ms " +
                    "(${schedulePlan.reason.logLabel})"
            }

            automaticRefreshJob =
                applicationScope.launch {
                    if (schedulePlan.delayMs > 0L) {
                        delay(schedulePlan.delayMs)
                    }
                    performAutomaticRefresh(key)
                }
        }

        private suspend fun performAutomaticRefresh(key: StrategyPackRefreshKey) {
            val currentJob = currentCoroutineContext()[Job]
            val shouldRun =
                schedulingMutex.withLock {
                    currentRefreshKey == key && automaticRefreshJob === currentJob && key.isAutomatic
                }
            if (!shouldRun) {
                Logger.i { "Automatic strategy-pack refresh canceled before execution" }
                return
            }

            val attemptedAt = clock.nowEpochMillis()
            runCatching {
                refreshExecutor.refresh(key)
            }.onSuccess { snapshot ->
                onRefreshSuccess(snapshot, key, currentJob, attemptedAt)
            }.onFailure { error ->
                onRefreshFailure(error, key, currentJob, attemptedAt)
            }
        }

        private suspend fun onRefreshSuccess(
            snapshot: StrategyPackSnapshot,
            key: StrategyPackRefreshKey,
            currentJob: Job?,
            attemptedAt: Long,
        ) {
            val shouldPublish =
                schedulingMutex.withLock {
                    if (currentRefreshKey != key || automaticRefreshJob !== currentJob) {
                        false
                    } else {
                        consecutiveAutomaticFailures = 0
                        lastSuccessfulFetchAtEpochMillis = snapshot.lastFetchedAtEpochMillis
                        true
                    }
                }
            if (shouldPublish) {
                statePublisher.publishSelectionForSnapshot(
                    snapshot = snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = null,
                    lastRefreshFailureCode = null,
                    lastRejectedSequence = null,
                )
                scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.TtlDue)
            }
        }

        private suspend fun onRefreshFailure(
            error: Throwable,
            key: StrategyPackRefreshKey,
            currentJob: Job?,
            attemptedAt: Long,
        ) {
            val shouldPublish =
                schedulingMutex.withLock {
                    if (currentRefreshKey != key || automaticRefreshJob !== currentJob) {
                        false
                    } else {
                        consecutiveAutomaticFailures += 1
                        true
                    }
                }
            if (shouldPublish) {
                val retryDelayMs =
                    schedulingMutex.withLock {
                        refreshBackoffPolicy.nextFailureBackoff(consecutiveAutomaticFailures)
                    }
                Logger.w(error) {
                    "Automatic strategy-pack refresh failed; retrying in $retryDelayMs ms"
                }
                statePublisher.publishSelectionForSnapshot(
                    snapshot = stateStore.state.value.snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = error.message,
                    lastRefreshFailureCode = error.toFailureCode(),
                    lastRejectedSequence = error.toRejectedSequence(),
                )
                scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.RetryBackoff)
            }
        }

        private fun cancelAutomaticRefreshLocked(because: String) {
            automaticRefreshJob?.cancel()
            if (automaticRefreshJob != null) {
                Logger.i { "Automatic strategy-pack refresh canceled because $because" }
            }
            automaticRefreshJob = null
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackServiceModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackService(service: DefaultStrategyPackService): StrategyPackService
}

@Module
@InstallIn(SingletonComponent::class)
object StrategyPackServiceTimingModule {
    @Provides
    @Named("strategyPackRefreshSuccessTtlMs")
    fun provideStrategyPackRefreshSuccessTtlMs(): Long = hoursToMillis(strategyPackRefreshSuccessTtlHours)

    @Provides
    @Named("strategyPackRefreshInitialFailureBackoffMs")
    fun provideStrategyPackRefreshInitialFailureBackoffMs(): Long =
        minutesToMillis(strategyPackRefreshInitialFailureBackoffMinutes)

    @Provides
    @Named("strategyPackRefreshMaxFailureBackoffMs")
    fun provideStrategyPackRefreshMaxFailureBackoffMs(): Long = hoursToMillis(strategyPackRefreshMaxFailureBackoffHours)

    private fun minutesToMillis(minutes: Long): Long = minutes * millisPerMinute

    private fun hoursToMillis(hours: Long): Long = minutesToMillis(hours * minutesPerHour)
}

private const val strategyPackRefreshSuccessTtlHours = 6L
private const val strategyPackRefreshInitialFailureBackoffMinutes = 15L
private const val strategyPackRefreshMaxFailureBackoffHours = 6L
private const val minutesPerHour = 60L
private const val millisPerMinute = 60_000L

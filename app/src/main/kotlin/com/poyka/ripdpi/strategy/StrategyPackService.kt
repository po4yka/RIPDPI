package com.poyka.ripdpi.strategy

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.StrategyPackRefreshFailureCode
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackSettingsModel
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.acceptedSequenceOrNull
import com.poyka.ripdpi.data.normalizeStrategyPackRefreshPolicy
import com.poyka.ripdpi.data.resolveSelection
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
                val initialSnapshot = repository.loadSnapshot()

                schedulingMutex.withLock {
                    currentRefreshKey = initialKey
                    lastSuccessfulFetchAtEpochMillis = initialSnapshot.lastFetchedAtEpochMillis
                    consecutiveAutomaticFailures = 0
                }

                publishSelectionForSnapshot(
                    snapshot = initialSnapshot,
                    key = initialKey,
                    lastRefreshAttemptAtEpochMillis = initialSnapshot.lastFetchedAtEpochMillis,
                    lastRefreshError = null,
                )
                scheduleAutomaticRefresh(initialKey, StrategyPackRefreshScheduleReason.Startup)

                var firstEmission = true
                settingsJob =
                    applicationScope.launch {
                        appSettingsRepository.settings
                            .map { settings -> StrategyPackRefreshKey(settings.toStrategyPackSettingsModel()) }
                            .distinctUntilChanged()
                            .collect { key ->
                                if (firstEmission) {
                                    firstEmission = false
                                    if (key == initialKey) {
                                        return@collect
                                    }
                                }
                                handleRelevantSettingsChange(key)
                            }
                    }
            }
        }

        override suspend fun refreshNow() {
            val settings = appSettingsRepository.snapshot().toStrategyPackSettingsModel()
            val key = StrategyPackRefreshKey(settings)
            val attemptedAt = clock.nowEpochMillis()
            runCatching {
                repository.refreshSnapshot(
                    channel = settings.channel,
                    allowRollbackOverride = settings.allowRollbackOverride,
                )
            }.onSuccess { snapshot ->
                schedulingMutex.withLock {
                    currentRefreshKey = key
                    consecutiveAutomaticFailures = 0
                    lastSuccessfulFetchAtEpochMillis = snapshot.lastFetchedAtEpochMillis
                }
                publishSelectionForSnapshot(
                    snapshot = snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = null,
                    lastRefreshFailureCode = null,
                    lastRejectedSequence = null,
                )
                scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.ManualRefreshReseed)
            }.onFailure { error ->
                val currentState = stateStore.state.value
                publishSelectionForSnapshot(
                    snapshot = currentState.snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = error.message,
                    lastRefreshFailureCode = error.toFailureCode(),
                    lastRejectedSequence = error.toRejectedSequence(),
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
            publishSelectionForSnapshot(
                snapshot = runtimeState.snapshot,
                key = key,
                lastRefreshAttemptAtEpochMillis = runtimeState.lastRefreshAttemptAtEpochMillis,
                lastRefreshError = runtimeState.lastRefreshError,
                lastRefreshFailureCode = runtimeState.lastRefreshFailureCode,
                lastRejectedSequence = runtimeState.lastRejectedSequence,
            )
            scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.RelevantSettingsChanged)
        }

        private suspend fun publishSelectionForSnapshot(
            snapshot: com.poyka.ripdpi.data.StrategyPackSnapshot,
            key: StrategyPackRefreshKey,
            lastRefreshAttemptAtEpochMillis: Long? = snapshot.lastFetchedAtEpochMillis,
            lastRefreshError: String?,
            lastRefreshFailureCode: StrategyPackRefreshFailureCode? = null,
            lastRejectedSequence: Long? = null,
        ) {
            val selection =
                snapshot.catalog.resolveSelection(
                    pinnedPackId = key.pinnedPackId,
                    pinnedPackVersion = key.pinnedPackVersion,
                )
            stateStore.update(
                StrategyPackRuntimeState(
                    snapshot = snapshot,
                    selectedPackId = selection.pack?.id,
                    selectedPackVersion = selection.pack?.version,
                    tlsProfileSetId = selection.tlsProfileSet?.id,
                    tlsProfileCatalogVersion = selection.tlsProfileSet?.catalogVersion,
                    tlsProfileAllowedIds = selection.tlsProfileSet?.allowedProfileIds.orEmpty(),
                    tlsRotationEnabled = selection.tlsProfileSet?.rotationEnabled == true,
                    tlsProfileEchPolicy = selection.tlsProfileSet?.echPolicy,
                    tlsProfileProxyModeNotice = selection.tlsProfileSet?.proxyModeNotice,
                    tlsProfileAcceptanceCorpusRef = selection.tlsProfileSet?.acceptanceCorpusRef,
                    morphPolicyId = selection.morphPolicy?.id,
                    morphPolicy = selection.morphPolicy,
                    transportModuleIds = selection.transportModules.map { it.id },
                    featureFlags = selection.featureFlags.associate { it.id to it.enabled },
                    lastResolvedAtEpochMillis = clock.nowEpochMillis(),
                    lastRefreshAttemptAtEpochMillis = lastRefreshAttemptAtEpochMillis,
                    lastRefreshError = lastRefreshError,
                    lastRefreshFailureCode = lastRefreshFailureCode,
                    lastAcceptedSequence = snapshot.acceptedSequenceOrNull(),
                    lastRejectedSequence = lastRejectedSequence,
                    refreshPolicy = key.refreshPolicy,
                ),
            )
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
                        delayMs = nextDelayForLocked(reason),
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
                repository.refreshSnapshot(
                    channel = key.channel,
                    allowRollbackOverride = key.allowRollbackOverride,
                )
            }.onSuccess { snapshot ->
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
                if (!shouldPublish) {
                    return
                }

                publishSelectionForSnapshot(
                    snapshot = snapshot,
                    key = key,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = null,
                    lastRefreshFailureCode = null,
                    lastRejectedSequence = null,
                )
                scheduleAutomaticRefresh(key, StrategyPackRefreshScheduleReason.TtlDue)
            }.onFailure { error ->
                val shouldPublish =
                    schedulingMutex.withLock {
                        if (currentRefreshKey != key || automaticRefreshJob !== currentJob) {
                            false
                        } else {
                            consecutiveAutomaticFailures += 1
                            true
                        }
                    }
                if (!shouldPublish) {
                    return
                }

                val retryDelayMs = schedulingMutex.withLock { nextFailureBackoffLocked() }
                Logger.w(error) {
                    "Automatic strategy-pack refresh failed; retrying in $retryDelayMs ms"
                }
                publishSelectionForSnapshot(
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

        private fun nextDelayForLocked(reason: StrategyPackRefreshScheduleReason): Long =
            when (reason) {
                StrategyPackRefreshScheduleReason.Startup -> {
                    val lastSuccessfulFetchAt = lastSuccessfulFetchAtEpochMillis ?: return 0L
                    val dueAt = lastSuccessfulFetchAt + refreshSuccessTtlMs
                    (dueAt - clock.nowEpochMillis()).coerceAtLeast(0L)
                }

                StrategyPackRefreshScheduleReason.RelevantSettingsChanged -> {
                    0L
                }

                StrategyPackRefreshScheduleReason.TtlDue -> {
                    refreshSuccessTtlMs
                }

                StrategyPackRefreshScheduleReason.RetryBackoff -> {
                    nextFailureBackoffLocked()
                }

                StrategyPackRefreshScheduleReason.ManualRefreshReseed -> {
                    refreshSuccessTtlMs
                }
            }

        private fun nextFailureBackoffLocked(): Long {
            val exponent = (consecutiveAutomaticFailures - 1).coerceAtLeast(0).coerceAtMost(maxBackoffExponent)
            return (initialFailureBackoffMs * (1L shl exponent)).coerceAtMost(maxFailureBackoffMs)
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

private data class StrategyPackRefreshKey(
    val channel: String,
    val refreshPolicy: String,
    val pinnedPackId: String,
    val pinnedPackVersion: String,
    val allowRollbackOverride: Boolean,
) {
    constructor(settings: StrategyPackSettingsModel) : this(
        channel = settings.channel,
        refreshPolicy = normalizeStrategyPackRefreshPolicy(settings.refreshPolicy),
        pinnedPackId = settings.pinnedPackId,
        pinnedPackVersion = settings.pinnedPackVersion,
        allowRollbackOverride = settings.allowRollbackOverride,
    )

    val isAutomatic: Boolean
        get() = refreshPolicy == StrategyPackRefreshPolicyAutomatic
}

private fun Throwable.toFailureCode(): StrategyPackRefreshFailureCode? =
    (this as? StrategyPackRefreshException)?.failureCode

private fun Throwable.toRejectedSequence(): Long? = (this as? StrategyPackRefreshException)?.rejectedSequence

private data class StrategyPackSchedulePlan(
    val delayMs: Long,
    val reason: StrategyPackRefreshScheduleReason,
)

private enum class StrategyPackRefreshScheduleReason(
    val logLabel: String,
) {
    Startup("startup"),
    RelevantSettingsChanged("relevant-settings-changed"),
    TtlDue("ttl-due"),
    RetryBackoff("retry-backoff"),
    ManualRefreshReseed("manual-refresh-reseed"),
}

private const val strategyPackRefreshSuccessTtlHours = 6L
private const val strategyPackRefreshInitialFailureBackoffMinutes = 15L
private const val strategyPackRefreshMaxFailureBackoffHours = 6L
private const val minutesPerHour = 60L
private const val millisPerMinute = 60_000L
private const val maxBackoffExponent = 5

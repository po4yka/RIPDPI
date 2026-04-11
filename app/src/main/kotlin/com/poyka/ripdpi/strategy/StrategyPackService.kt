package com.poyka.ripdpi.strategy

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.DefaultStrategyPackRefreshPolicy
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.normalizeStrategyPackRefreshPolicy
import com.poyka.ripdpi.data.resolveSelection
import com.poyka.ripdpi.data.toStrategyPackSettingsModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
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
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : StrategyPackService {
        @Volatile private var initialized = false
        private var settingsJob: Job? = null

        override fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            applicationScope.launch {
                loadAndPublishSnapshot(repository.loadSnapshot(), lastRefreshError = null)
            }
            settingsJob =
                applicationScope.launch {
                    appSettingsRepository.settings.collectLatest { settings ->
                        val strategyPackSettings = settings.toStrategyPackSettingsModel()
                        publishState(
                            snapshot = stateStore.state.value.snapshot,
                            pinnedPackId = strategyPackSettings.pinnedPackId,
                            pinnedPackVersion = strategyPackSettings.pinnedPackVersion,
                            refreshPolicy = strategyPackSettings.refreshPolicy,
                            lastRefreshAttemptAtEpochMillis = stateStore.state.value.lastRefreshAttemptAtEpochMillis,
                            lastRefreshError = stateStore.state.value.lastRefreshError,
                        )
                        if (normalizeStrategyPackRefreshPolicy(strategyPackSettings.refreshPolicy) ==
                            StrategyPackRefreshPolicyAutomatic
                        ) {
                            runCatching {
                                repository.refreshSnapshot(strategyPackSettings.channel)
                            }.onSuccess { refreshed ->
                                loadAndPublishSnapshot(refreshed, lastRefreshError = null)
                            }.onFailure { error ->
                                Logger.w(error) { "Strategy-pack refresh skipped" }
                                publishState(
                                    snapshot = stateStore.state.value.snapshot,
                                    pinnedPackId = strategyPackSettings.pinnedPackId,
                                    pinnedPackVersion = strategyPackSettings.pinnedPackVersion,
                                    refreshPolicy = strategyPackSettings.refreshPolicy,
                                    lastRefreshAttemptAtEpochMillis = clock.nowEpochMillis(),
                                    lastRefreshError = error.message,
                                )
                            }
                        }
                    }
                }
        }

        override suspend fun refreshNow() {
            val settings = appSettingsRepository.snapshot().toStrategyPackSettingsModel()
            val attemptedAt = clock.nowEpochMillis()
            runCatching {
                repository.refreshSnapshot(settings.channel)
            }.onSuccess { snapshot ->
                loadAndPublishSnapshot(
                    snapshot = snapshot,
                    lastRefreshError = null,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                )
            }.onFailure { error ->
                publishState(
                    snapshot = stateStore.state.value.snapshot,
                    pinnedPackId = settings.pinnedPackId,
                    pinnedPackVersion = settings.pinnedPackVersion,
                    refreshPolicy = settings.refreshPolicy,
                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                    lastRefreshError = error.message,
                )
                throw error
            }
        }

        private suspend fun loadAndPublishSnapshot(
            snapshot: com.poyka.ripdpi.data.StrategyPackSnapshot,
            lastRefreshError: String?,
            lastRefreshAttemptAtEpochMillis: Long? = snapshot.lastFetchedAtEpochMillis,
        ) {
            val settings = appSettingsRepository.snapshot().toStrategyPackSettingsModel()
            publishState(
                snapshot = snapshot,
                pinnedPackId = settings.pinnedPackId,
                pinnedPackVersion = settings.pinnedPackVersion,
                refreshPolicy = settings.refreshPolicy,
                lastRefreshAttemptAtEpochMillis = lastRefreshAttemptAtEpochMillis,
                lastRefreshError = lastRefreshError,
            )
        }

        private fun publishState(
            snapshot: com.poyka.ripdpi.data.StrategyPackSnapshot,
            pinnedPackId: String = "",
            pinnedPackVersion: String = "",
            refreshPolicy: String = DefaultStrategyPackRefreshPolicy,
            lastRefreshAttemptAtEpochMillis: Long? = snapshot.lastFetchedAtEpochMillis,
            lastRefreshError: String?,
        ) {
            val selection =
                snapshot.catalog.resolveSelection(
                    pinnedPackId = pinnedPackId,
                    pinnedPackVersion = pinnedPackVersion,
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
                    morphPolicyId = selection.morphPolicy?.id,
                    morphPolicy = selection.morphPolicy,
                    transportModuleIds = selection.transportModules.map { it.id },
                    featureFlags = selection.featureFlags.associate { it.id to it.enabled },
                    lastResolvedAtEpochMillis = clock.nowEpochMillis(),
                    lastRefreshAttemptAtEpochMillis = lastRefreshAttemptAtEpochMillis,
                    lastRefreshError = lastRefreshError,
                    refreshPolicy = normalizeStrategyPackRefreshPolicy(refreshPolicy),
                ),
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackServiceModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackService(service: DefaultStrategyPackService): StrategyPackService
}

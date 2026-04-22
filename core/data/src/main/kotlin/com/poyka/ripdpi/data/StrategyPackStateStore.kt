package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class StrategyPackRuntimeState(
    val snapshot: StrategyPackSnapshot = StrategyPackSnapshot(),
    val selectedPackId: String? = null,
    val selectedPackVersion: String? = null,
    val tlsProfileSetId: String? = null,
    val tlsProfileCatalogVersion: String? = null,
    val tlsProfileAllowedIds: List<String> = emptyList(),
    val tlsRotationEnabled: Boolean = false,
    val tlsProfileEchPolicy: String? = null,
    val tlsProfileProxyModeNotice: String? = null,
    val tlsProfileAcceptanceCorpusRef: String? = null,
    val morphPolicyId: String? = null,
    val morphPolicy: StrategyPackMorphPolicy? = null,
    val transportModuleIds: List<String> = emptyList(),
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val lastResolvedAtEpochMillis: Long? = null,
    val lastRefreshAttemptAtEpochMillis: Long? = null,
    val lastRefreshError: String? = null,
    val lastRefreshFailureCode: StrategyPackRefreshFailureCode? = null,
    val lastAcceptedSequence: Long? = null,
    val lastRejectedSequence: Long? = null,
    val refreshPolicy: String = DefaultStrategyPackRefreshPolicy,
)

interface StrategyPackStateStore {
    val state: StateFlow<StrategyPackRuntimeState>

    fun update(state: StrategyPackRuntimeState)
}

@Singleton
class InMemoryStrategyPackStateStore
    @Inject
    constructor() : StrategyPackStateStore {
        private val mutableState = MutableStateFlow(StrategyPackRuntimeState())

        override val state: StateFlow<StrategyPackRuntimeState> = mutableState.asStateFlow()

        override fun update(state: StrategyPackRuntimeState) {
            mutableState.value = state
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackStateStoreModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackStateStore(store: InMemoryStrategyPackStateStore): StrategyPackStateStore
}

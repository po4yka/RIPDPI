package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DefaultTlsProfileCatalogVersion
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class RuntimeExperimentSelection(
    val strategyPackId: String? = null,
    val strategyPackVersion: String? = null,
    val tlsProfileId: String? = null,
    val tlsProfileCatalogVersion: String = DefaultTlsProfileCatalogVersion,
    val morphPolicyId: String? = null,
    val featureFlags: Map<String, Boolean> = emptyMap(),
)

interface RuntimeExperimentSelectionProvider {
    fun current(): RuntimeExperimentSelection
}

@Singleton
class DefaultRuntimeExperimentSelectionProvider
    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
        strategyPackStateStore: StrategyPackStateStore,
        @ApplicationIoScope scope: CoroutineScope,
    ) : RuntimeExperimentSelectionProvider {
        private val currentState = AtomicReference(RuntimeExperimentSelection())

        init {
            scope.launch {
                combine(appSettingsRepository.settings, strategyPackStateStore.state) { settings, strategyPackState ->
                    RuntimeExperimentSelection(
                        strategyPackId = strategyPackState.selectedPackId,
                        strategyPackVersion = strategyPackState.selectedPackVersion,
                        tlsProfileId = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
                        tlsProfileCatalogVersion =
                            strategyPackState.tlsProfileCatalogVersion ?: DefaultTlsProfileCatalogVersion,
                        morphPolicyId = strategyPackState.morphPolicyId,
                        featureFlags = strategyPackState.featureFlags,
                    )
                }.collectLatest(currentState::set)
            }
        }

        override fun current(): RuntimeExperimentSelection = currentState.get()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeExperimentSelectionModule {
    @Binds
    @Singleton
    abstract fun bindRuntimeExperimentSelectionProvider(
        provider: DefaultRuntimeExperimentSelectionProvider,
    ): RuntimeExperimentSelectionProvider
}

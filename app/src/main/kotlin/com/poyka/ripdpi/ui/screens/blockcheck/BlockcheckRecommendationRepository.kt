package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.toStrategyActivationYaml
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject

/** Outcome of applying a strategy recommendation to the persisted app settings. */
sealed interface BlockcheckApplyOutcome {
    data object Applied : BlockcheckApplyOutcome

    data object Unregistered : BlockcheckApplyOutcome

    data class ReloadFailed(
        val message: String,
    ) : BlockcheckApplyOutcome
}

/** Persists a chosen strategy candidate and reloads the native config. UI-state free. */
interface BlockcheckRecommendationRepository {
    suspend fun applyStrategy(
        strategyId: String,
        candidates: List<StrategyProbeCandidate>,
    ): BlockcheckApplyOutcome
}

class DefaultBlockcheckRecommendationRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val reloader: BlockcheckStrategyReloader,
    ) : BlockcheckRecommendationRepository {
        override suspend fun applyStrategy(
            strategyId: String,
            candidates: List<StrategyProbeCandidate>,
        ): BlockcheckApplyOutcome {
            val candidate =
                candidates.firstOrNull { it.id == strategyId }
                    ?: return BlockcheckApplyOutcome.Unregistered
            appSettingsRepository.update {
                strategyChainYaml = candidate.toStrategyActivationYaml()
                candidate.configDsl?.let(::setRawStrategyChainDsl)
            }
            val reloadError = reloader.reloadConfig()
            return if (reloadError == null) {
                BlockcheckApplyOutcome.Applied
            } else {
                BlockcheckApplyOutcome.ReloadFailed(reloadError)
            }
        }
    }

@Module
@InstallIn(ViewModelComponent::class)
abstract class BlockcheckRecommendationModule {
    @Binds
    abstract fun bindBlockcheckRecommendationRepository(
        repository: DefaultBlockcheckRecommendationRepository,
    ): BlockcheckRecommendationRepository
}

@Module
@InstallIn(ViewModelComponent::class)
object BlockcheckStrategyReloaderModule {
    @Provides
    fun provideBlockcheckStrategyReloader(): BlockcheckStrategyReloader = NativeBlockcheckStrategyReloader()
}

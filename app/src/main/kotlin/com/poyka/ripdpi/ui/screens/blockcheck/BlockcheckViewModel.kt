package com.poyka.ripdpi.ui.screens.blockcheck

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeFailureKind
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.min

private val DefaultBlockcheckDomains =
    persistentListOf(
        "www.youtube.com",
        "www.facebook.com",
        "t.me",
        "twitter.com",
        "www.instagram.com",
    )

enum class BlockcheckRunState {
    Idle,
    Running,
    Complete,
    Error,
}

data class BlockcheckSiteDiagnosis(
    val domain: String,
    val diagnosis: BlockLayerDiagnosis,
    val probeVerdict: String? = null,
)

data class BlockcheckUiState(
    val runState: BlockcheckRunState = BlockcheckRunState.Idle,
    val domains: ImmutableList<String> = DefaultBlockcheckDomains,
    val results: ImmutableList<StrategyProbeResult> = persistentListOf(),
    val rankedStrategies: ImmutableList<RankedStrategyProbeResult> = persistentListOf(),
    val diagnoses: ImmutableList<BlockcheckSiteDiagnosis> = persistentListOf(),
    val recommendedStrategyId: String? = null,
    val recommendedStrategyLabel: String? = null,
    val totalExpectedResults: Int = 0,
    val noStrategiesRegistered: Boolean = false,
    val message: String? = null,
    @StringRes val messageRes: Int? = null,
) {
    val isRunning: Boolean = runState == BlockcheckRunState.Running
    val dnsTamperDetected: Boolean =
        results.any {
            it.dnsTampered ||
                it.failureKind == StrategyProbeFailureKind.DnsTampered
        }
    val bestStrategy: RankedStrategyProbeResult? = rankedStrategies.firstOrNull()
    val primaryDiagnosis: BlockcheckSiteDiagnosis? = diagnoses.firstOrNull()
    val progress: Float =
        if (totalExpectedResults <= 0) {
            0f
        } else {
            (results.size.toFloat() / totalExpectedResults.toFloat()).coerceIn(0f, 1f)
        }
}

@HiltViewModel
class BlockcheckViewModel
    @Inject
    constructor(
        private val orchestrator: BlockcheckProbeOrchestrator,
        private val recommendationRepository: BlockcheckRecommendationRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(BlockcheckUiState())
        val uiState: StateFlow<BlockcheckUiState> = mutableUiState.asStateFlow()

        private var probeJob: Job? = null
        private var lastCandidates: List<StrategyProbeCandidate> = emptyList()

        fun updateDomainsText(value: String) {
            val domains =
                value
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
            mutableUiState.update { state ->
                state.copy(domains = domains.toImmutableList())
            }
        }

        fun startProbe() {
            val domains = uiState.value.domains.ifEmpty { DefaultBlockcheckDomains }
            probeJob?.cancel()
            probeJob =
                viewModelScope.launch {
                    val candidateResult = runCatching { orchestrator.listCandidates() }
                    candidateResult
                        .onFailure { error ->
                            mutableUiState.update {
                                it.copy(runState = BlockcheckRunState.Error, message = error.userMessage())
                            }
                        }.onSuccess { candidates ->
                            lastCandidates = candidates
                            if (candidates.isEmpty()) {
                                mutableUiState.value =
                                    BlockcheckUiState(
                                        runState = BlockcheckRunState.Complete,
                                        domains = domains,
                                        noStrategiesRegistered = true,
                                    )
                                return@launch
                            }
                            orchestrator
                                .run(domains, candidates)
                                .catch { error ->
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    mutableUiState.update { state ->
                                        state.copy(runState = BlockcheckRunState.Error, message = error.userMessage())
                                    }
                                }.collect { snapshot ->
                                    mutableUiState.value = snapshot
                                }
                        }
                }
        }

        fun cancelProbe() {
            probeJob?.cancel()
            mutableUiState.update { state ->
                state.copy(
                    runState = if (state.results.isEmpty()) BlockcheckRunState.Idle else BlockcheckRunState.Complete,
                    messageRes = R.string.blockcheck_message_probe_cancelled,
                )
            }
        }

        fun applyBestStrategy() {
            val best = uiState.value.bestStrategy ?: return
            applyStrategy(best.strategyId)
        }

        fun applyRecommendedStrategy() {
            val strategyId = uiState.value.recommendedStrategyId ?: return
            applyStrategy(strategyId)
        }

        private fun applyStrategy(strategyId: String) {
            viewModelScope.launch {
                when (val outcome = recommendationRepository.applyStrategy(strategyId, lastCandidates)) {
                    BlockcheckApplyOutcome.Unregistered -> {
                        mutableUiState.update {
                            it.copy(messageRes = R.string.blockcheck_message_strategy_unregistered)
                        }
                    }

                    BlockcheckApplyOutcome.Applied -> {
                        mutableUiState.update { state ->
                            state.copy(
                                message = null,
                                messageRes = R.string.blockcheck_message_strategy_applied,
                            )
                        }
                    }

                    is BlockcheckApplyOutcome.ReloadFailed -> {
                        mutableUiState.update { state ->
                            state.copy(
                                message = outcome.message,
                                messageRes = null,
                                runState = BlockcheckRunState.Error,
                            )
                        }
                    }
                }
            }
        }

        fun exportReport(): String = encodeBlockcheckReport(uiState.value)
    }

interface BlockcheckDiagnosisRunner {
    suspend fun diagnose(domains: List<String>): List<BlockcheckSiteDiagnosis>
}

class DefaultBlockcheckDiagnosisRunner
    @Inject
    constructor(
        private val scanner: DomainReachabilityScanner,
    ) : BlockcheckDiagnosisRunner {
        override suspend fun diagnose(domains: List<String>): List<BlockcheckSiteDiagnosis> =
            scanner
                .withMaxConcurrent(min(domains.size.coerceAtLeast(1), BlockcheckDiagnosisMaxDomains))
                .scan(domains, stubIps = emptySet())
                .mapNotNull(::diagnoseReachability)
    }

@Module
@InstallIn(ViewModelComponent::class)
abstract class BlockcheckDiagnosisModule {
    @Binds
    abstract fun bindBlockcheckDiagnosisRunner(runner: DefaultBlockcheckDiagnosisRunner): BlockcheckDiagnosisRunner
}

interface BlockcheckStrategyReloader {
    fun reloadConfig(): String?
}

class NativeBlockcheckStrategyReloader : BlockcheckStrategyReloader {
    private val runtime = NativeStrategyConfigRuntime()

    override fun reloadConfig(): String? = runtime.reloadConfig()
}

private fun Throwable.userMessage(): String = localizedMessage ?: javaClass.simpleName

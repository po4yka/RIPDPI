package com.poyka.ripdpi.ui.screens.tuner

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeRankingAccumulator
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.toStrategyActivationYaml
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private const val DefaultMaxStrategies = 4
private const val DefaultMaxDomains = 3
private const val DefaultProbeIntervalMs = 750L
private const val DefaultProbeTimeoutMs = 10_000L

private val DefaultStrategyTunerDomains =
    listOf(
        "www.youtube.com",
        "t.me",
        "vk.com",
    )

enum class StrategyTunerRunState {
    Idle,
    Running,
    Complete,
    Error,
}

data class StrategyTunerBudget(
    val maxStrategies: Int = DefaultMaxStrategies,
    val maxDomains: Int = DefaultMaxDomains,
    val maxTotalProbes: Int = DefaultMaxStrategies * DefaultMaxDomains,
    val probeIntervalMs: Long = DefaultProbeIntervalMs,
    val timeoutMs: Long = DefaultProbeTimeoutMs,
)

data class StrategyTunerUiState(
    val runState: StrategyTunerRunState = StrategyTunerRunState.Idle,
    val domainsText: String = DefaultStrategyTunerDomains.joinToString("\n"),
    val activeDomains: List<String> = DefaultStrategyTunerDomains,
    val results: List<StrategyProbeResult> = emptyList(),
    val rankedStrategies: List<RankedStrategyProbeResult> = emptyList(),
    val budget: StrategyTunerBudget = StrategyTunerBudget(),
    val totalExpectedResults: Int = 0,
    val message: String? = null,
    @StringRes val messageRes: Int? = null,
    val appliedStrategyId: String? = null,
) {
    val isRunning: Boolean = runState == StrategyTunerRunState.Running
    val bestStrategy: RankedStrategyProbeResult? = rankedStrategies.firstOrNull()
    val progress: Float =
        if (totalExpectedResults <= 0) {
            0f
        } else {
            (results.size.toFloat() / totalExpectedResults.toFloat()).coerceIn(0f, 1f)
        }
}

sealed interface StrategyTunerEvent {
    data class Started(
        val domains: List<String>,
        val candidates: List<StrategyProbeCandidate>,
        val totalExpectedResults: Int,
    ) : StrategyTunerEvent

    data class Result(
        val result: StrategyProbeResult,
    ) : StrategyTunerEvent

    data object Complete : StrategyTunerEvent
}

interface StrategyTunerRunner {
    val budget: StrategyTunerBudget

    fun run(domains: List<String>): Flow<StrategyTunerEvent>

    suspend fun apply(strategyId: String): String?
}

@HiltViewModel
class StrategyTunerViewModel
    @Inject
    constructor(
        private val runner: StrategyTunerRunner,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(StrategyTunerUiState(budget = runner.budget))
        val uiState: StateFlow<StrategyTunerUiState> = mutableUiState.asStateFlow()

        private var probeJob: Job? = null

        fun updateDomainsText(value: String) {
            mutableUiState.update { state ->
                state.copy(domainsText = value, activeDomains = value.normalizedDomains(state.budget.maxDomains))
            }
        }

        fun start() {
            val domains = uiState.value.domainsText.normalizedDomains(runner.budget.maxDomains)
            if (domains.isEmpty()) {
                mutableUiState.update {
                    it.copy(message = stringResolver.getString(R.string.strategy_tuner_message_add_domain))
                }
                return
            }
            probeJob?.cancel()
            probeJob =
                viewModelScope.launch {
                    var collected = persistentListOf<StrategyProbeResult>()
                    val rankingAccumulator = StrategyProbeRankingAccumulator()
                    runner
                        .run(domains)
                        .catch { error ->
                            if (error is CancellationException) {
                                throw error
                            }
                            mutableUiState.update {
                                it.copy(runState = StrategyTunerRunState.Error, message = error.userMessage())
                            }
                        }.collect { event ->
                            when (event) {
                                StrategyTunerEvent.Complete -> {
                                    mutableUiState.update {
                                        it.copy(
                                            runState = StrategyTunerRunState.Complete,
                                            message =
                                                stringResolver.getString(
                                                    R.string.strategy_tuner_message_sweep_complete,
                                                ),
                                        )
                                    }
                                }

                                is StrategyTunerEvent.Result -> {
                                    collected = collected.add(event.result)
                                    rankingAccumulator.add(event.result)
                                    mutableUiState.update {
                                        it.copy(
                                            results = collected,
                                            rankedStrategies = rankingAccumulator.rankedStrategies(),
                                        )
                                    }
                                }

                                is StrategyTunerEvent.Started -> {
                                    mutableUiState.update {
                                        it.copy(
                                            runState = StrategyTunerRunState.Running,
                                            activeDomains = event.domains,
                                            results = emptyList(),
                                            rankedStrategies = emptyList(),
                                            totalExpectedResults = event.totalExpectedResults,
                                            message = null,
                                            appliedStrategyId = null,
                                        )
                                    }
                                }
                            }
                        }
                }
        }

        fun cancel() {
            probeJob?.cancel()
            mutableUiState.update {
                it.copy(
                    runState =
                        if (it.results.isEmpty()) {
                            StrategyTunerRunState.Idle
                        } else {
                            StrategyTunerRunState.Complete
                        },
                    message = stringResolver.getString(R.string.strategy_tuner_message_sweep_cancelled),
                )
            }
        }

        fun apply(strategyId: String) {
            if (uiState.value.isRunning) return
            viewModelScope.launch {
                val error = runner.apply(strategyId)
                mutableUiState.update {
                    it.copy(
                        runState = if (error == null) it.runState else StrategyTunerRunState.Error,
                        message = error,
                        messageRes = if (error == null) R.string.strategy_tuner_message_strategy_applied else null,
                        appliedStrategyId = if (error == null) strategyId else it.appliedStrategyId,
                    )
                }
            }
        }
    }

@Singleton
class DefaultStrategyTunerRunner
    @Inject
    constructor(
        private val candidateProvider: StrategyProbeCandidateProvider,
        private val probeService: StrategyProbeService,
        private val appSettingsRepository: AppSettingsRepository,
        private val stringResolver: StringResolver,
    ) : StrategyTunerRunner {
        override val budget: StrategyTunerBudget = StrategyTunerBudget()
        private var lastCandidates: List<StrategyProbeCandidate> = emptyList()

        override fun run(domains: List<String>): Flow<StrategyTunerEvent> =
            flow {
                val boundedDomains = domains.take(budget.maxDomains)
                val candidates =
                    candidateProvider
                        .listCandidates()
                        .distinctBy(
                            StrategyProbeCandidate::id,
                        ).take(budget.maxStrategies)
                lastCandidates = candidates
                val expected = min(candidates.size * boundedDomains.size, budget.maxTotalProbes)
                emit(
                    StrategyTunerEvent.Started(
                        domains = boundedDomains,
                        candidates = candidates,
                        totalExpectedResults = expected,
                    ),
                )
                if (expected == 0) {
                    emit(StrategyTunerEvent.Complete)
                    return@flow
                }
                probeService
                    .run(
                        StrategyProbeConfig(
                            testDomains = boundedDomains,
                            timeoutMs = budget.timeoutMs,
                            maxStrategies = candidates.size,
                            maxTotalProbes = budget.maxTotalProbes,
                            probeIntervalMs = budget.probeIntervalMs,
                            candidateIds = candidates.map(StrategyProbeCandidate::id),
                        ),
                    ).collect { result -> emit(StrategyTunerEvent.Result(result)) }
                emit(StrategyTunerEvent.Complete)
            }

        override suspend fun apply(strategyId: String): String? {
            val candidate =
                lastCandidates.firstOrNull { it.id == strategyId }
                    ?: candidateProvider.listCandidates().firstOrNull { it.id == strategyId }
                    ?: return stringResolver.getString(R.string.strategy_tuner_strategy_unregistered)
            appSettingsRepository.update {
                strategyChainYaml = candidate.toStrategyActivationYaml()
                candidate.configDsl?.let(::setRawStrategyChainDsl)
            }
            return NativeStrategyConfigRuntime().reloadConfig()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyTunerModule {
    @Binds
    @Singleton
    abstract fun bindStrategyTunerRunner(runner: DefaultStrategyTunerRunner): StrategyTunerRunner
}

private fun String.normalizedDomains(maxDomains: Int): List<String> =
    lines()
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(maxDomains.coerceAtLeast(0))
        .toList()

private fun Throwable.userMessage(): String = localizedMessage ?: javaClass.simpleName

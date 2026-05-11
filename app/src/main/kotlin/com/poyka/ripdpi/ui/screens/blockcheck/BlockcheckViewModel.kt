package com.poyka.ripdpi.ui.screens.blockcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeFailureKind
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.summarizeStrategyProbeResults
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

private val DefaultBlockcheckDomains =
    listOf(
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

data class BlockcheckUiState(
    val runState: BlockcheckRunState = BlockcheckRunState.Idle,
    val domains: List<String> = DefaultBlockcheckDomains,
    val results: List<StrategyProbeResult> = emptyList(),
    val rankedStrategies: List<RankedStrategyProbeResult> = emptyList(),
    val totalExpectedResults: Int = 0,
    val noStrategiesRegistered: Boolean = false,
    val message: String? = null,
) {
    val isRunning: Boolean = runState == BlockcheckRunState.Running
    val dnsTamperDetected: Boolean =
        results.any {
            it.dnsTampered ||
                it.failureKind == StrategyProbeFailureKind.DnsTampered
        }
    val bestStrategy: RankedStrategyProbeResult? = rankedStrategies.firstOrNull()
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
        private val probeService: StrategyProbeService,
        private val candidateProvider: StrategyProbeCandidateProvider,
        private val appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(BlockcheckUiState())
        val uiState: StateFlow<BlockcheckUiState> = mutableUiState.asStateFlow()

        private var probeJob: Job? = null
        private var lastCandidates: List<StrategyProbeCandidate> = emptyList()
        internal var strategyReloader: BlockcheckStrategyReloader? = null

        fun updateDomainsText(value: String) {
            val domains =
                value
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
            mutableUiState.update { state ->
                state.copy(domains = domains)
            }
        }

        fun startProbe() {
            val domains = uiState.value.domains.ifEmpty { DefaultBlockcheckDomains }
            probeJob?.cancel()
            probeJob =
                viewModelScope.launch {
                    val candidateResult = runCatching { candidateProvider.listCandidates() }
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
                            collectProbe(domains, candidates)
                        }
                }
        }

        fun cancelProbe() {
            probeJob?.cancel()
            mutableUiState.update { state ->
                state.copy(
                    runState = if (state.results.isEmpty()) BlockcheckRunState.Idle else BlockcheckRunState.Complete,
                    message = "Probe cancelled",
                )
            }
        }

        fun applyBestStrategy() {
            val best = uiState.value.bestStrategy ?: return
            viewModelScope.launch {
                val candidate = lastCandidates.firstOrNull { it.id == best.strategyId }
                if (candidate == null) {
                    mutableUiState.update { it.copy(message = "Strategy is no longer registered") }
                    return@launch
                }
                appSettingsRepository.update {
                    strategyChainYaml = candidate.toActivationYaml()
                    candidate.configDsl?.let(::setRawStrategyChainDsl)
                }
                val reloadError =
                    (strategyReloader ?: NativeBlockcheckStrategyReloader()).reloadConfig()
                mutableUiState.update { state ->
                    state.copy(
                        message = reloadError ?: "Strategy applied",
                        runState = if (reloadError == null) state.runState else BlockcheckRunState.Error,
                    )
                }
            }
        }

        fun exportReport(): String = encodeBlockcheckReport(uiState.value)

        private suspend fun collectProbe(
            domains: List<String>,
            candidates: List<StrategyProbeCandidate>,
        ) {
            val collected = mutableListOf<StrategyProbeResult>()
            mutableUiState.value =
                BlockcheckUiState(
                    runState = BlockcheckRunState.Running,
                    domains = domains,
                    totalExpectedResults = candidates.size * domains.size,
                )
            try {
                val config = StrategyProbeConfig(testDomains = domains, maxStrategies = candidates.size)
                probeService.run(config).collect { result ->
                    collected += result
                    mutableUiState.update { state ->
                        state.copy(
                            results = collected.toList(),
                            rankedStrategies = summarizeStrategyProbeResults(collected).rankedStrategies,
                        )
                    }
                }
                mutableUiState.update { it.copy(runState = BlockcheckRunState.Complete, message = "Probe complete") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableUiState.update { state ->
                    state.copy(runState = BlockcheckRunState.Error, message = error.userMessage())
                }
            }
        }
    }

interface BlockcheckStrategyReloader {
    fun reloadConfig(): String?
}

class NativeBlockcheckStrategyReloader : BlockcheckStrategyReloader {
    private val runtime = NativeStrategyConfigRuntime()

    override fun reloadConfig(): String? = runtime.reloadConfig()
}

private val BlockcheckReportJson = Json { prettyPrint = true }

fun encodeBlockcheckReport(state: BlockcheckUiState): String {
    val payload =
        JsonObject(
            mapOf(
                "state" to JsonPrimitive(state.runState.name.lowercase()),
                "domains" to JsonArray(state.domains.map(::JsonPrimitive)),
                "results" to JsonArray(state.results.map(::encodeProbeResult)),
                "ranked_strategies" to JsonArray(state.rankedStrategies.map(::encodeRankedStrategy)),
            ),
        )
    return BlockcheckReportJson.encodeToString(JsonObject.serializer(), payload)
}

private fun encodeProbeResult(result: StrategyProbeResult): JsonObject =
    JsonObject(
        mapOf(
            "strategy_id" to JsonPrimitive(result.strategyId),
            "strategy_label" to JsonPrimitive(result.strategyLabel),
            "domain" to JsonPrimitive(result.domain),
            "success" to JsonPrimitive(result.success),
            "latency_ms" to JsonPrimitive(result.latencyMs),
            "dns_tampered" to JsonPrimitive(result.dnsTampered),
            "failure_kind" to JsonPrimitive(result.failureKind?.name),
            "error" to JsonPrimitive(result.error),
        ),
    )

private fun encodeRankedStrategy(result: RankedStrategyProbeResult): JsonObject =
    JsonObject(
        mapOf(
            "strategy_id" to JsonPrimitive(result.strategyId),
            "strategy_label" to JsonPrimitive(result.strategyLabel),
            "total" to JsonPrimitive(result.total),
            "successes" to JsonPrimitive(result.successes),
            "success_rate" to JsonPrimitive(result.successRate),
            "average_latency_ms" to JsonPrimitive(result.averageLatencyMs),
            "dns_tampered_count" to JsonPrimitive(result.dnsTamperedCount),
        ),
    )

private fun StrategyProbeCandidate.toActivationYaml(): String =
    if (id.startsWith(LuaStrategyIdPrefix)) {
        luaActivationYaml()
    } else {
        """
        strategies:
          - id: "$id"
            label: "$label"
        """.trimIndent()
    }

private const val LuaStrategyIdPrefix = "lua:"

private fun StrategyProbeCandidate.luaActivationYaml(): String {
    val function = id.removePrefix(LuaStrategyIdPrefix)
    return (
        listOf(
            "version: 1",
            "strategies:",
            "  - id: \"${id.yamlQuote()}\"",
            "    steps:",
            "      - type: lua",
            "        function: \"${function.yamlQuote()}\"",
            "        script_paths:",
        ) +
            luaScriptPaths.map { path -> "          - \"${path.yamlQuote()}\"" }
    ).joinToString(separator = "\n")
}

private fun String.yamlQuote(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun Throwable.userMessage(): String = localizedMessage ?: javaClass.simpleName

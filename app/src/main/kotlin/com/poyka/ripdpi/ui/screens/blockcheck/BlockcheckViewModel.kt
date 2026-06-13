package com.poyka.ripdpi.ui.screens.blockcheck

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosisMapper
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeFailureKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRankingAccumulator
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.toStrategyActivationYaml
import com.poyka.ripdpi.serialization.RipDpiPrettyDefaultsJson
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import kotlin.math.min

private val DefaultBlockcheckDomains =
    listOf(
        "www.youtube.com",
        "www.facebook.com",
        "t.me",
        "twitter.com",
        "www.instagram.com",
    )

private const val BlockcheckDiagnosisMaxDomains = 3

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
    val domains: List<String> = DefaultBlockcheckDomains,
    val results: List<StrategyProbeResult> = emptyList(),
    val rankedStrategies: List<RankedStrategyProbeResult> = emptyList(),
    val diagnoses: List<BlockcheckSiteDiagnosis> = emptyList(),
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
        private val probeService: StrategyProbeService,
        private val candidateProvider: StrategyProbeCandidateProvider,
        private val appSettingsRepository: AppSettingsRepository,
        private val diagnosisRunner: BlockcheckDiagnosisRunner,
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
                            collectDiagnosis(domains, candidates)
                            collectProbe(domains, candidates)
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
                val candidate = lastCandidates.firstOrNull { it.id == strategyId }
                if (candidate == null) {
                    mutableUiState.update { it.copy(messageRes = R.string.blockcheck_message_strategy_unregistered) }
                    return@launch
                }
                appSettingsRepository.update {
                    strategyChainYaml = candidate.toStrategyActivationYaml()
                    candidate.configDsl?.let(::setRawStrategyChainDsl)
                }
                val reloadError =
                    (strategyReloader ?: NativeBlockcheckStrategyReloader()).reloadConfig()
                mutableUiState.update { state ->
                    state.copy(
                        message = reloadError,
                        messageRes = if (reloadError == null) R.string.blockcheck_message_strategy_applied else null,
                        runState = if (reloadError == null) state.runState else BlockcheckRunState.Error,
                    )
                }
            }
        }

        fun exportReport(): String = encodeBlockcheckReport(uiState.value)

        private suspend fun collectDiagnosis(
            domains: List<String>,
            candidates: List<StrategyProbeCandidate>,
        ) {
            val diagnosisDomains = domains.take(BlockcheckDiagnosisMaxDomains)
            val diagnoses =
                runCatching { diagnosisRunner.diagnose(diagnosisDomains) }
                    .getOrElse { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        emptyList()
                    }
            mutableUiState.update { state ->
                state.copy(
                    domains = domains,
                    diagnoses = diagnoses,
                    recommendedStrategyId = diagnoses.recommendedCandidate(candidates)?.id,
                    recommendedStrategyLabel = diagnoses.recommendedCandidate(candidates)?.label,
                )
            }
        }

        private suspend fun collectProbe(
            domains: List<String>,
            candidates: List<StrategyProbeCandidate>,
        ) {
            var collected = persistentListOf<StrategyProbeResult>()
            val rankingAccumulator = StrategyProbeRankingAccumulator()
            mutableUiState.value =
                BlockcheckUiState(
                    runState = BlockcheckRunState.Running,
                    domains = domains,
                    diagnoses = mutableUiState.value.diagnoses,
                    recommendedStrategyId = mutableUiState.value.recommendedStrategyId,
                    recommendedStrategyLabel = mutableUiState.value.recommendedStrategyLabel,
                    totalExpectedResults = candidates.size * domains.size,
                )
            val config = StrategyProbeConfig(testDomains = domains, maxStrategies = candidates.size)
            probeService
                .run(config)
                .catch { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    mutableUiState.update { state ->
                        state.copy(runState = BlockcheckRunState.Error, message = error.userMessage())
                    }
                }.collect { result ->
                    collected = collected.add(result)
                    rankingAccumulator.add(result)
                    mutableUiState.update { state ->
                        state.copy(
                            results = collected,
                            rankedStrategies = rankingAccumulator.rankedStrategies(),
                            recommendedStrategyId =
                                state.diagnoses
                                    .recommendedCandidate(
                                        candidates = candidates,
                                        rankedStrategies = rankingAccumulator.rankedStrategies(),
                                    )?.id,
                            recommendedStrategyLabel =
                                state.diagnoses
                                    .recommendedCandidate(
                                        candidates = candidates,
                                        rankedStrategies = rankingAccumulator.rankedStrategies(),
                                    )?.label,
                        )
                    }
                }
            mutableUiState.update { state ->
                val diagnoses =
                    if (state.diagnoses.isEmpty()) {
                        diagnoseFromStrategyResults(state.results)
                    } else {
                        state.diagnoses
                    }
                state.copy(
                    runState = BlockcheckRunState.Complete,
                    messageRes = R.string.blockcheck_message_probe_complete,
                    diagnoses = diagnoses,
                    recommendedStrategyId =
                        diagnoses
                            .recommendedCandidate(
                                candidates = candidates,
                                rankedStrategies = state.rankedStrategies,
                            )?.id,
                    recommendedStrategyLabel =
                        diagnoses
                            .recommendedCandidate(
                                candidates = candidates,
                                rankedStrategies = state.rankedStrategies,
                            )?.label,
                )
            }
        }
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

private val BlockcheckReportJson = RipDpiPrettyDefaultsJson

fun encodeBlockcheckReport(state: BlockcheckUiState): String {
    val payload =
        JsonObject(
            mapOf(
                "state" to JsonPrimitive(state.runState.name.lowercase()),
                "domains" to JsonArray(state.domains.map(::JsonPrimitive)),
                "diagnoses" to JsonArray(state.diagnoses.map(::encodeDiagnosis)),
                "recommended_strategy_id" to JsonPrimitive(state.recommendedStrategyId),
                "results" to JsonArray(state.results.map(::encodeProbeResult)),
                "ranked_strategies" to JsonArray(state.rankedStrategies.map(::encodeRankedStrategy)),
            ),
        )
    return BlockcheckReportJson.encodeToString(JsonObject.serializer(), payload)
}

private fun encodeDiagnosis(diagnosis: BlockcheckSiteDiagnosis): JsonObject =
    JsonObject(
        mapOf(
            "domain" to JsonPrimitive(diagnosis.domain),
            "layer" to
                JsonPrimitive(
                    diagnosis.diagnosis.layer.name
                        .lowercase(),
                ),
            "bypass_class" to
                JsonPrimitive(
                    diagnosis.diagnosis.bypassClass.name
                        .lowercase(),
                ),
            "confidence" to
                JsonPrimitive(
                    diagnosis.diagnosis.confidence.name
                        .lowercase(),
                ),
            "reason_code" to JsonPrimitive(diagnosis.diagnosis.reasonCode),
            "probe_verdict" to JsonPrimitive(diagnosis.probeVerdict),
        ),
    )

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

private fun Throwable.userMessage(): String = localizedMessage ?: javaClass.simpleName

private fun diagnoseReachability(result: DomainReachabilityResult): BlockcheckSiteDiagnosis? {
    val diagnosis =
        when (result.verdict) {
            DomainVerdict.OK -> null

            DomainVerdict.DNS_FAIL,
            DomainVerdict.FAKE_IP,
            -> BlockLayerDiagnosisMapper.fromDpiError(com.poyka.ripdpi.core.detection.dpi.DpiProbeError.DnsFail)

            DomainVerdict.ISP_PAGE -> BlockLayerDiagnosisMapper.forHttpBlockpage()

            DomainVerdict.TCP16_BAND,
            DomainVerdict.TLS_VERSION_BLOCK,
            -> BlockLayerDiagnosisMapper.forTlsVersionBlock()

            DomainVerdict.BLOCKED,
            DomainVerdict.UNREACHABLE,
            -> result.firstProbeDiagnosis()
        } ?: return null
    return BlockcheckSiteDiagnosis(
        domain = result.domain,
        diagnosis = diagnosis,
        probeVerdict = result.verdict.name,
    )
}

private fun DomainReachabilityResult.firstProbeDiagnosis(): BlockLayerDiagnosis =
    listOf(tls13, tls12, http)
        .firstNotNullOfOrNull { attempt ->
            BlockLayerDiagnosisMapper.fromDpiError(attempt.error)
                ?: attempt.status.toDiagnosis()
        } ?: BlockLayerDiagnosisMapper.forUnknownBlocked()

private fun AttemptStatus.toDiagnosis(): BlockLayerDiagnosis? =
    when (this) {
        AttemptStatus.BLOCKED,
        AttemptStatus.REDIR_SUSPICIOUS,
        AttemptStatus.ISP_PAGE,
        -> BlockLayerDiagnosisMapper.forHttpBlockpage()

        AttemptStatus.TCP16_BAND_TIMEOUT -> BlockLayerDiagnosisMapper.forTlsVersionBlock()

        AttemptStatus.OK,
        AttemptStatus.REDIR_OK,
        AttemptStatus.FAKE_IP,
        AttemptStatus.ERROR,
        -> null
    }

private fun diagnoseFromStrategyResults(results: List<StrategyProbeResult>): List<BlockcheckSiteDiagnosis> =
    results
        .asSequence()
        .filter { result -> !result.success }
        .groupBy(StrategyProbeResult::domain)
        .mapNotNull { (domain, domainResults) ->
            val diagnosis =
                when {
                    domainResults.any { it.dnsTampered || it.failureKind == StrategyProbeFailureKind.DnsTampered } -> {
                        BlockLayerDiagnosisMapper.forDnsTampering()
                    }

                    domainResults.any { it.failureKind == StrategyProbeFailureKind.Timeout } -> {
                        BlockLayerDiagnosis(
                            layer = BlockLayer.IP_BLOCK,
                            bypassClass = BypassStrategyClass.FAKE_PACKET_TTL,
                            confidence = EvidenceConfidence.LOW,
                            reasonCode = "strategy_probe_timeout",
                        )
                    }

                    domainResults.any { it.failureKind == StrategyProbeFailureKind.ConnectionFailed } -> {
                        BlockLayerDiagnosisMapper.forUnknownBlocked()
                    }

                    else -> {
                        null
                    }
                } ?: return@mapNotNull null
            BlockcheckSiteDiagnosis(domain = domain, diagnosis = diagnosis)
        }

private fun List<BlockcheckSiteDiagnosis>.recommendedCandidate(
    candidates: List<StrategyProbeCandidate>,
    rankedStrategies: List<RankedStrategyProbeResult> = emptyList(),
): StrategyProbeCandidate? {
    val recommendation = firstOrNull()?.diagnosis?.bypassClass ?: return rankedStrategies.bestCandidate(candidates)
    return rankedStrategies
        .mapNotNull { ranked -> candidates.firstOrNull { candidate -> candidate.id == ranked.strategyId } }
        .firstOrNull { candidate -> candidate.matches(recommendation) }
        ?: candidates.firstOrNull { candidate -> candidate.matches(recommendation) }
        ?: rankedStrategies.bestCandidate(candidates)
}

private fun List<RankedStrategyProbeResult>.bestCandidate(
    candidates: List<StrategyProbeCandidate>,
): StrategyProbeCandidate? =
    firstOrNull()?.strategyId?.let { strategyId -> candidates.firstOrNull { it.id == strategyId } }

private fun StrategyProbeCandidate.matches(recommendation: BypassStrategyClass): Boolean {
    val haystack = "$id $label ${configDsl.orEmpty()}".lowercase()
    return when (recommendation) {
        BypassStrategyClass.ENCRYPTED_DNS -> {
            false
        }

        BypassStrategyClass.TLS_RECORD_SPLIT -> {
            haystack.contains("tlsrec") || haystack.contains("split")
        }

        BypassStrategyClass.FAKE_PACKET_TTL -> {
            haystack.contains("fake") || haystack.contains("oob") ||
                haystack.contains("ttl")
        }

        BypassStrategyClass.HOST_HEADER_SPLIT -> {
            haystack.contains("host") || haystack.contains("split_host")
        }

        BypassStrategyClass.STRATEGY_PROBE_WINNER -> {
            true
        }
    }
}

package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosisMapper
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
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
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

internal const val BlockcheckDiagnosisMaxDomains = 3

/**
 * Owns the two-phase blockcheck pipeline: a diagnosis pass followed by a streamed
 * strategy-probe pass with incremental ranking and recommendation recompute.
 *
 * [run] is a cold flow: it first emits a clean [BlockcheckRunState.Running] snapshot
 * carrying the diagnosis, then one snapshot per probe result with the rolling ranking
 * and recommendation, then a terminal [BlockcheckRunState.Complete] snapshot.
 */
class BlockcheckProbeOrchestrator
    @Inject
    constructor(
        private val probeService: StrategyProbeService,
        private val candidateProvider: StrategyProbeCandidateProvider,
        private val diagnosisRunner: BlockcheckDiagnosisRunner,
    ) {
        suspend fun listCandidates(): List<StrategyProbeCandidate> = candidateProvider.listCandidates()

        suspend fun diagnose(domains: List<String>): List<BlockcheckSiteDiagnosis> {
            val diagnosisDomains = domains.take(BlockcheckDiagnosisMaxDomains)
            return runCatching { diagnosisRunner.diagnose(diagnosisDomains) }
                .getOrElse { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    emptyList()
                }
        }

        fun run(
            domains: List<String>,
            candidates: List<StrategyProbeCandidate>,
        ): Flow<BlockcheckUiState> =
            flow {
                val diagnoses = diagnose(domains)
                emit(
                    BlockcheckUiState(
                        runState = BlockcheckRunState.Running,
                        domains = domains.toImmutableList(),
                        diagnoses = diagnoses.toImmutableList(),
                        recommendedStrategyId = diagnoses.recommendedCandidate(candidates)?.id,
                        recommendedStrategyLabel = diagnoses.recommendedCandidate(candidates)?.label,
                        totalExpectedResults = candidates.size * domains.size,
                    ),
                )
                var collected = persistentListOf<StrategyProbeResult>()
                val rankingAccumulator = StrategyProbeRankingAccumulator()
                val config = StrategyProbeConfig(testDomains = domains, maxStrategies = candidates.size)
                probeService.run(config).collect { result ->
                    collected = collected.adding(result)
                    rankingAccumulator.add(result)
                    val ranked = rankingAccumulator.rankedStrategies()
                    emit(
                        BlockcheckUiState(
                            runState = BlockcheckRunState.Running,
                            domains = domains.toImmutableList(),
                            results = collected,
                            rankedStrategies = ranked.toImmutableList(),
                            diagnoses = diagnoses.toImmutableList(),
                            recommendedStrategyId =
                                diagnoses
                                    .recommendedCandidate(candidates = candidates, rankedStrategies = ranked)
                                    ?.id,
                            recommendedStrategyLabel =
                                diagnoses
                                    .recommendedCandidate(candidates = candidates, rankedStrategies = ranked)
                                    ?.label,
                            totalExpectedResults = candidates.size * domains.size,
                        ),
                    )
                }
                val ranked = rankingAccumulator.rankedStrategies()
                val finalDiagnoses =
                    if (diagnoses.isEmpty()) {
                        diagnoseFromStrategyResults(collected)
                    } else {
                        diagnoses
                    }
                emit(
                    BlockcheckUiState(
                        runState = BlockcheckRunState.Complete,
                        domains = domains.toImmutableList(),
                        results = collected,
                        rankedStrategies = ranked.toImmutableList(),
                        diagnoses = finalDiagnoses.toImmutableList(),
                        recommendedStrategyId =
                            finalDiagnoses
                                .recommendedCandidate(candidates = candidates, rankedStrategies = ranked)
                                ?.id,
                        recommendedStrategyLabel =
                            finalDiagnoses
                                .recommendedCandidate(candidates = candidates, rankedStrategies = ranked)
                                ?.label,
                        totalExpectedResults = candidates.size * domains.size,
                        messageRes = R.string.blockcheck_message_probe_complete,
                    ),
                )
            }
    }

internal fun diagnoseReachability(result: DomainReachabilityResult): BlockcheckSiteDiagnosis? {
    val diagnosis =
        when (result.verdict) {
            DomainVerdict.OK -> null

            DomainVerdict.DNS_FAIL,
            DomainVerdict.FAKE_IP,
            -> BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.DnsFail)

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

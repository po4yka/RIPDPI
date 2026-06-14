package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

internal class FakeBlockcheckDiagnosisRunner(
    private val diagnoses: List<BlockcheckSiteDiagnosis> = emptyList(),
) : BlockcheckDiagnosisRunner {
    override suspend fun diagnose(domains: List<String>): List<BlockcheckSiteDiagnosis> = diagnoses
}

internal class FakeStrategyProbeService(
    private val results: List<StrategyProbeResult>,
) : StrategyProbeService {
    override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> =
        flow {
            results.forEach { emit(it) }
        }
}

internal class StreamingStrategyProbeService : StrategyProbeService {
    private val results = MutableSharedFlow<StrategyProbeResult>(extraBufferCapacity = 8)

    override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> = results

    fun emit(result: StrategyProbeResult): Boolean = results.tryEmit(result)
}

internal class FakeStrategyProbeCandidateProvider(
    private val candidates: List<StrategyProbeCandidate> = defaultBlockcheckCandidates(),
) : StrategyProbeCandidateProvider {
    override suspend fun listCandidates(): List<StrategyProbeCandidate> = candidates
}

internal class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettings.getDefaultInstance())

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state
                .value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

internal class FakeBlockcheckStrategyReloader(
    private val reloadError: String? = null,
) : BlockcheckStrategyReloader {
    var reloadCount = 0

    override fun reloadConfig(): String? {
        reloadCount += 1
        return reloadError
    }
}

internal fun defaultBlockcheckCandidates(): List<StrategyProbeCandidate> =
    listOf(
        StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto(balanced)"),
        StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
    )

internal fun blockcheckDiagnosis(
    domain: String,
    layer: BlockLayer,
    bypassClass: BypassStrategyClass,
): BlockcheckSiteDiagnosis =
    BlockcheckSiteDiagnosis(
        domain = domain,
        diagnosis =
            BlockLayerDiagnosis(
                layer = layer,
                bypassClass = bypassClass,
                confidence = EvidenceConfidence.HIGH,
                reasonCode = "test",
            ),
    )

internal fun blockcheckProbeResult(
    strategyId: String,
    success: Boolean,
    latencyMs: Long,
): StrategyProbeResult =
    StrategyProbeResult(
        strategyId = strategyId,
        strategyLabel = if (strategyId == "fake") "Fake packet" else "Split",
        domain = "youtube.com",
        success = success,
        latencyMs = latencyMs,
    )

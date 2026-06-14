package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeFailureKind
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.summarizeStrategyProbeResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockcheckProbeOrchestratorTest {
    @Test
    fun `run emits running snapshot first then complete`() =
        runTest {
            val orchestrator =
                orchestrator(
                    results = listOf(orchestratorProbeResult("fake", success = true, latencyMs = 90)),
                )

            val snapshots =
                orchestrator
                    .run(domains = listOf("youtube.com"), candidates = orchestratorCandidates())
                    .toList()

            assertEquals(BlockcheckRunState.Running, snapshots.first().runState)
            assertTrue(snapshots.first().results.isEmpty())
            assertEquals(BlockcheckRunState.Complete, snapshots.last().runState)
        }

    @Test
    fun `run accumulates ranking incrementally and matches summarize`() =
        runTest {
            val emitted =
                listOf(
                    orchestratorProbeResult("split", success = false, latencyMs = 300),
                    orchestratorProbeResult("fake", success = false, latencyMs = 100),
                    orchestratorProbeResult("split", success = true, latencyMs = 50),
                )
            val orchestrator = orchestrator(results = emitted)

            val snapshots =
                orchestrator
                    .run(domains = listOf("youtube.com"), candidates = orchestratorCandidates())
                    .toList()

            // snapshots[0] is the clean Running snapshot, then one per emitted result, then Complete.
            val perResult = snapshots.drop(1).dropLast(1)
            assertEquals(emitted.size, perResult.size)
            perResult.forEachIndexed { index, snapshot ->
                val cumulative = emitted.take(index + 1)
                assertEquals(
                    summarizeStrategyProbeResults(cumulative).rankedStrategies,
                    snapshot.rankedStrategies,
                )
                assertEquals(index + 1, snapshot.results.size)
            }
            assertEquals(
                summarizeStrategyProbeResults(emitted).rankedStrategies,
                snapshots.last().rankedStrategies,
            )
        }

    @Test
    fun `run maps diagnosis bypass class to matching recommended candidate`() =
        runTest {
            val orchestrator =
                orchestrator(
                    results =
                        listOf(
                            orchestratorProbeResult("split", success = false, latencyMs = 300),
                            orchestratorProbeResult("tlsrec_split", success = true, latencyMs = 90),
                        ),
                    diagnosisRunner =
                        FakeBlockcheckDiagnosisRunner(
                            listOf(
                                orchestratorSiteDiagnosis(
                                    domain = "youtube.com",
                                    layer = BlockLayer.SNI_BASED_RESET,
                                    bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
                                ),
                            ),
                        ),
                    candidates =
                        listOf(
                            StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
                            StrategyProbeCandidate("tlsrec_split", "TLS record split", "[tcp]\ntlsrec split"),
                        ),
                )

            val complete =
                orchestrator
                    .run(
                        domains = listOf("youtube.com"),
                        candidates =
                            listOf(
                                StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
                                StrategyProbeCandidate("tlsrec_split", "TLS record split", "[tcp]\ntlsrec split"),
                            ),
                    ).toList()
                    .last()

            assertEquals("tlsrec_split", complete.recommendedStrategyId)
            assertEquals("TLS record split", complete.recommendedStrategyLabel)
        }

    @Test
    fun `run falls back to diagnoseFromStrategyResults when diagnoses empty`() =
        runTest {
            val orchestrator =
                orchestrator(
                    results =
                        listOf(
                            orchestratorProbeResult(
                                "split",
                                success = false,
                                latencyMs = 300,
                                dnsTampered = true,
                            ),
                        ),
                    diagnosisRunner = FakeBlockcheckDiagnosisRunner(emptyList()),
                )

            val complete =
                orchestrator
                    .run(domains = listOf("youtube.com"), candidates = orchestratorCandidates())
                    .toList()
                    .last()

            assertEquals(BlockLayer.DNS_POISONING, complete.primaryDiagnosis?.diagnosis?.layer)
            assertEquals(
                BypassStrategyClass.ENCRYPTED_DNS,
                complete.primaryDiagnosis?.diagnosis?.bypassClass,
            )
        }

    @Test
    fun `run rethrows cancellation from probe stream`() =
        runTest {
            val orchestrator =
                orchestrator(
                    probeService = CancellingStrategyProbeService(),
                    results = emptyList(),
                )

            try {
                orchestrator
                    .run(domains = listOf("youtube.com"), candidates = orchestratorCandidates())
                    .toList()
                fail("expected CancellationException")
            } catch (expected: CancellationException) {
                assertEquals("cancelled mid-stream", expected.message)
            }
        }

    @Test
    fun `diagnose returns empty on non-cancellation error`() =
        runTest {
            val orchestrator =
                orchestrator(
                    diagnosisRunner = ThrowingBlockcheckDiagnosisRunner(IllegalStateException("boom")),
                    results = emptyList(),
                )

            assertTrue(orchestrator.diagnose(listOf("youtube.com")).isEmpty())
        }

    @Test
    fun `diagnose rethrows cancellation`() =
        runTest {
            val orchestrator =
                orchestrator(
                    diagnosisRunner = ThrowingBlockcheckDiagnosisRunner(CancellationException("cancel")),
                    results = emptyList(),
                )

            try {
                orchestrator.diagnose(listOf("youtube.com"))
                fail("expected CancellationException")
            } catch (expected: CancellationException) {
                assertEquals("cancel", expected.message)
            }
        }

    private fun orchestrator(
        probeService: StrategyProbeService? = null,
        candidateProvider: StrategyProbeCandidateProvider? = null,
        candidates: List<StrategyProbeCandidate> = orchestratorCandidates(),
        diagnosisRunner: BlockcheckDiagnosisRunner = FakeBlockcheckDiagnosisRunner(),
        results: List<StrategyProbeResult>,
    ): BlockcheckProbeOrchestrator =
        BlockcheckProbeOrchestrator(
            probeService = probeService ?: FakeStrategyProbeService(results),
            candidateProvider = candidateProvider ?: FakeStrategyProbeCandidateProvider(candidates),
            diagnosisRunner = diagnosisRunner,
        )
}

private fun orchestratorCandidates(): List<StrategyProbeCandidate> =
    listOf(
        StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto(balanced)"),
        StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
    )

private fun orchestratorSiteDiagnosis(
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

private fun orchestratorProbeResult(
    strategyId: String,
    success: Boolean,
    latencyMs: Long,
    dnsTampered: Boolean = false,
): StrategyProbeResult =
    StrategyProbeResult(
        strategyId = strategyId,
        strategyLabel = if (strategyId == "fake") "Fake packet" else strategyId,
        domain = "youtube.com",
        success = success,
        latencyMs = latencyMs,
        failureKind = if (!success && !dnsTampered) StrategyProbeFailureKind.ConnectionFailed else null,
        dnsTampered = dnsTampered,
    )

private class ThrowingBlockcheckDiagnosisRunner(
    private val error: Throwable,
) : BlockcheckDiagnosisRunner {
    override suspend fun diagnose(domains: List<String>): List<BlockcheckSiteDiagnosis> = throw error
}

private class CancellingStrategyProbeService : StrategyProbeService {
    override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> =
        flow {
            throw CancellationException("cancelled mid-stream")
        }
}

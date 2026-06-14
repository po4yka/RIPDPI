package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockcheckRecommendationRepositoryTest {
    @Test
    fun `applyStrategy writes winner config and reloads`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val repository = DefaultBlockcheckRecommendationRepository(settings, reloader)

            val outcome =
                repository.applyStrategy(
                    strategyId = "fake",
                    candidates = candidates(),
                )

            assertEquals(BlockcheckApplyOutcome.Applied, outcome)
            assertEquals(1, reloader.reloadCount)
            val appliedStep =
                settings
                    .snapshot()
                    .effectiveTcpChainSteps()
                    .single()
            assertEquals(TcpChainStepKind.Fake, appliedStep.kind)
        }

    @Test
    fun `applyStrategy sets chain steps from configDsl when present`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val repository = DefaultBlockcheckRecommendationRepository(settings, reloader)

            repository.applyStrategy(
                strategyId = "fake",
                candidates = listOf(StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)")),
            )

            // configDsl is parsed into the persisted TCP chain (setRawStrategyChainDsl).
            val steps = settings.snapshot().effectiveTcpChainSteps()
            assertTrue(steps.any { it.kind == TcpChainStepKind.Fake })
        }

    @Test
    fun `applyStrategy returns Unregistered without touching settings or reloader`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val repository = DefaultBlockcheckRecommendationRepository(settings, reloader)
            val before = settings.snapshot()

            val outcome =
                repository.applyStrategy(
                    strategyId = "missing",
                    candidates = candidates(),
                )

            assertEquals(BlockcheckApplyOutcome.Unregistered, outcome)
            assertEquals(0, reloader.reloadCount)
            assertEquals(before, settings.snapshot())
        }

    @Test
    fun `applyStrategy returns ReloadFailed when reload reports an error`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader(reloadError = "native reload failed")
            val repository = DefaultBlockcheckRecommendationRepository(settings, reloader)

            val outcome =
                repository.applyStrategy(
                    strategyId = "fake",
                    candidates = candidates(),
                )

            assertEquals(BlockcheckApplyOutcome.ReloadFailed("native reload failed"), outcome)
            assertEquals(1, reloader.reloadCount)
        }

    @Test
    fun `applyStrategy writes class-matched recommended candidate`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val repository = DefaultBlockcheckRecommendationRepository(settings, reloader)

            val outcome =
                repository.applyStrategy(
                    strategyId = "tlsrec_split",
                    candidates =
                        listOf(
                            StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
                            StrategyProbeCandidate(
                                "tlsrec_split",
                                "TLS record split",
                                "[tcp]\ntlsrec sniext+1\nsplit host",
                            ),
                        ),
                )

            assertEquals(BlockcheckApplyOutcome.Applied, outcome)
            val steps = settings.snapshot().effectiveTcpChainSteps()
            assertTrue(steps.any { it.kind == TcpChainStepKind.TlsRec })
        }
}

private fun candidates(): List<StrategyProbeCandidate> =
    listOf(
        StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto(balanced)"),
        StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
    )

package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.InMemoryStrategyPackStateStore
import com.poyka.ripdpi.data.StrategyPackCatalog
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackDefinition
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.services.TestAppSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyPackManualRefreshTest {
    @Test
    fun `refreshNow completion keeps a pin chosen while the download ran`() =
        runTest {
            val manualStarted = CompletableDeferred<Unit>()
            val manualResult = CompletableDeferred<StrategyPackSnapshot>()
            val repository = GatedRepository(manualStarted, manualResult)
            val appSettingsRepository = TestAppSettingsRepository()
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                DefaultStrategyPackService(
                    repository = repository,
                    appSettingsRepository = appSettingsRepository,
                    stateStore = stateStore,
                    clock = StrategyPackClock { testScheduler.currentTime },
                    refreshSuccessTtlMs = 6L * 60L * 60L * 1000L,
                    initialFailureBackoffMs = 15L * 60L * 1000L,
                    maxFailureBackoffMs = 6L * 60L * 60L * 1000L,
                    applicationScope = backgroundScope,
                )
            service.initialize()
            runCurrent()
            runCurrent()

            val manual = backgroundScope.async { service.refreshNow() }
            manualStarted.await()
            // The user pins a pack while the manual download is still running.
            appSettingsRepository.update {
                setStrategyPackPinnedId("mobile")
                setStrategyPackPinnedVersion("2026.04.1")
            }
            runCurrent()
            runCurrent()
            assertEquals("mobile", stateStore.state.value.selectedPackId)

            manualResult.complete(twoPackSnapshot())
            manual.await()
            runCurrent()

            assertEquals("mobile", stateStore.state.value.selectedPackId)
        }

    /** The first refresh waits for [result]; later refreshes return at once. */
    private class GatedRepository(
        private val started: CompletableDeferred<Unit>,
        private val result: CompletableDeferred<StrategyPackSnapshot>,
    ) : StrategyPackRepository {
        private var refreshes = 0

        override suspend fun loadSnapshot(): StrategyPackLoadResult = StrategyPackLoadResult(twoPackSnapshot())

        override suspend fun refreshSnapshot(
            channel: String,
            allowRollbackOverride: Boolean,
        ): StrategyPackSnapshot {
            refreshes += 1
            if (refreshes > 1) return twoPackSnapshot()
            started.complete(Unit)
            return result.await()
        }
    }

    private companion object {
        fun twoPackSnapshot(): StrategyPackSnapshot =
            StrategyPackSnapshot(
                catalog =
                    StrategyPackCatalog(
                        channel = "stable",
                        sequence = 11L,
                        packs =
                            listOf(
                                StrategyPackDefinition(
                                    id = "baseline",
                                    version = "2026.04.0",
                                    title = "Baseline",
                                    description = "Baseline pack",
                                ),
                                StrategyPackDefinition(
                                    id = "mobile",
                                    version = "2026.04.1",
                                    title = "Mobile",
                                    description = "Mobile pack",
                                ),
                            ),
                    ),
                source = StrategyPackCatalogSourceDownloaded,
                // Fresh, so the startup refresh waits for its TTL.
                lastFetchedAtEpochMillis = 0L,
            )
    }
}

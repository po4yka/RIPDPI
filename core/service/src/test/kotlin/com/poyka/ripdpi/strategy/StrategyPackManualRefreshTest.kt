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

    @Test
    fun `refreshNow superseded by a channel switch does not publish the old channel catalog`() =
        runTest {
            val manualStarted = CompletableDeferred<Unit>()
            val manualResult = CompletableDeferred<StrategyPackSnapshot>()
            val stateStore = InMemoryStrategyPackStateStore()
            val appSettingsRepository = TestAppSettingsRepository()
            val repository = GatedRepository(manualStarted, manualResult)
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
            // The user switches to beta; the beta refresh finishes before the stable download.
            appSettingsRepository.update { setStrategyPackChannel("beta") }
            runCurrent()
            runCurrent()
            assertEquals("beta", stateStore.state.value.snapshot.catalog.channel)

            manualResult.complete(twoPackSnapshot("stable"))
            manual.await()
            runCurrent()

            assertEquals("beta", stateStore.state.value.snapshot.catalog.channel)
            // The cache that the next start loads keeps the beta catalog.
            assertEquals("beta", repository.cached?.catalog?.channel)
        }

    /**
     * The first refresh waits for [result]; later refreshes return at once. Like the real repository,
     * a refresh writes [cached] only when its isCurrent check passes.
     */
    private class GatedRepository(
        private val started: CompletableDeferred<Unit>,
        private val result: CompletableDeferred<StrategyPackSnapshot>,
    ) : StrategyPackRepository {
        private var refreshes = 0
        var cached: StrategyPackSnapshot? = null
            private set

        override suspend fun loadSnapshot(channel: String): StrategyPackLoadResult =
            StrategyPackLoadResult(twoPackSnapshot())

        override suspend fun refreshSnapshot(
            channel: String,
            allowRollbackOverride: Boolean,
            isCurrent: suspend () -> Boolean,
        ): StrategyPackSnapshot {
            refreshes += 1
            val snapshot =
                if (refreshes > 1) {
                    twoPackSnapshot(channel)
                } else {
                    started.complete(Unit)
                    result.await()
                }
            if (isCurrent()) cached = snapshot
            return snapshot
        }
    }

    private companion object {
        fun twoPackSnapshot(channel: String = "stable"): StrategyPackSnapshot =
            StrategyPackSnapshot(
                catalog =
                    StrategyPackCatalog(
                        channel = channel,
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

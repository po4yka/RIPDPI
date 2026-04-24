package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ControlPlaneCacheDegradation
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.InMemoryStrategyPackStateStore
import com.poyka.ripdpi.data.StrategyPackCatalog
import com.poyka.ripdpi.data.StrategyPackCatalogSourceBundled
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackRefreshFailureCode
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyManual
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyPackServiceTest {
    @Test
    fun `initialize is idempotent and does not duplicate startup refresh`() =
        runTest {
            val repository = FakeStrategyPackRepository(initialSnapshot = bundledSnapshot(), clock = clock())
            val service = newService(repository = repository)

            service.initialize()
            service.initialize()
            runCurrent()
            runCurrent()

            assertEquals(1, repository.loadSnapshotCalls)
            assertEquals(listOf("stable"), repository.refreshChannels)
        }

    @Test
    fun `unrelated settings changes do not trigger strategy-pack network io`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(initialSnapshot = downloadedSnapshot(fetchedAt = 0L), clock = clock())
            val appSettingsRepository = FakeAppSettingsRepository()
            val service =
                newService(
                    repository = repository,
                    appSettingsRepository = appSettingsRepository,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            assertTrue(repository.refreshChannels.isEmpty())

            appSettingsRepository.update {
                setDnsIp("8.8.8.8")
            }
            runCurrent()
            runCurrent()

            assertTrue(repository.refreshChannels.isEmpty())
        }

    @Test
    fun `startup refresh is deferred while downloaded snapshot is fresh`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(initialSnapshot = downloadedSnapshot(fetchedAt = 0L), clock = clock())
            val service = newService(repository = repository)

            service.initialize()
            runCurrent()
            runCurrent()

            assertTrue(repository.refreshChannels.isEmpty())

            advanceTimeBy(testRefreshSuccessTtlMs - 1L)
            runCurrent()
            assertTrue(repository.refreshChannels.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            runCurrent()

            assertEquals(listOf("stable"), repository.refreshChannels)
        }

    @Test
    fun `startup refresh runs immediately when cached snapshot is bundled`() =
        runTest {
            val repository = FakeStrategyPackRepository(initialSnapshot = bundledSnapshot(), clock = clock())
            val service = newService(repository = repository)

            service.initialize()
            runCurrent()
            runCurrent()

            assertEquals(listOf("stable"), repository.refreshChannels)
        }

    @Test
    fun `initialize publishes cache degradation from the initial load result`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(
                    initialSnapshot = downloadedSnapshot(fetchedAt = 0L),
                    clock = clock(),
                    initialLoadDegradation =
                        ControlPlaneCacheDegradation(
                            code = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                            detail = "Unexpected EOF",
                        ),
                )
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                newService(
                    repository = repository,
                    stateStore = stateStore,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            assertEquals(
                ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                stateStore.state.value.cacheDegradationCode,
            )
            assertEquals("Unexpected EOF", stateStore.state.value.cacheDegradationDetail)
        }

    @Test
    fun `channel and pin changes trigger immediate refresh while same key does not`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(initialSnapshot = downloadedSnapshot(fetchedAt = 0L), clock = clock())
            val appSettingsRepository = FakeAppSettingsRepository()
            val service =
                newService(
                    repository = repository,
                    appSettingsRepository = appSettingsRepository,
                )

            service.initialize()
            runCurrent()
            runCurrent()
            assertTrue(repository.refreshChannels.isEmpty())

            appSettingsRepository.update {
                setStrategyPackChannel("beta")
            }
            runCurrent()
            runCurrent()
            assertEquals(listOf("beta"), repository.refreshChannels)

            appSettingsRepository.update {
                setStrategyPackChannel(" BETA ")
            }
            runCurrent()
            runCurrent()
            assertEquals(listOf("beta"), repository.refreshChannels)

            appSettingsRepository.update {
                setStrategyPackPinnedId("mobile-2026")
                setStrategyPackPinnedVersion("2026.04.1")
            }
            runCurrent()
            runCurrent()
            assertEquals(listOf("beta", "beta"), repository.refreshChannels)

            appSettingsRepository.update {
                setStrategyPackAllowRollbackOverride(true)
            }
            runCurrent()
            runCurrent()
            assertEquals(listOf("beta", "beta", "beta"), repository.refreshChannels)
            assertEquals(listOf(false, false, true), repository.refreshOverrideFlags)
        }

    @Test
    fun `switching to manual policy cancels pending automatic refresh`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(initialSnapshot = downloadedSnapshot(fetchedAt = 0L), clock = clock())
            val appSettingsRepository = FakeAppSettingsRepository()
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                newService(
                    repository = repository,
                    appSettingsRepository = appSettingsRepository,
                    stateStore = stateStore,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            appSettingsRepository.update {
                setStrategyPackRefreshPolicy(StrategyPackRefreshPolicyManual)
            }
            runCurrent()
            runCurrent()

            advanceTimeBy(testRefreshSuccessTtlMs)
            runCurrent()

            assertTrue(repository.refreshChannels.isEmpty())
            assertEquals(StrategyPackRefreshPolicyManual, stateStore.state.value.refreshPolicy)
        }

    @Test
    fun `automatic refresh retries with exponential backoff and resets after success`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(
                    initialSnapshot = bundledSnapshot(),
                    clock = clock(),
                    refreshOutcomes =
                        ArrayDeque(
                            listOf(
                                RefreshOutcome.Failure(IllegalStateException("first")),
                                RefreshOutcome.Failure(IllegalStateException("second")),
                                RefreshOutcome.Success(downloadedSnapshot(fetchedAt = 0L)),
                            ),
                        ),
                )
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                newService(
                    repository = repository,
                    stateStore = stateStore,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            assertEquals(1, repository.refreshChannels.size)
            assertEquals("first", stateStore.state.value.lastRefreshError)

            advanceTimeBy(testInitialFailureBackoffMs - 1L)
            runCurrent()
            assertEquals(1, repository.refreshChannels.size)

            advanceTimeBy(1L)
            runCurrent()
            runCurrent()
            assertEquals(2, repository.refreshChannels.size)
            assertEquals("second", stateStore.state.value.lastRefreshError)

            advanceTimeBy(testInitialFailureBackoffMs * 2)
            runCurrent()
            runCurrent()
            assertEquals(3, repository.refreshChannels.size)
            assertNull(stateStore.state.value.lastRefreshError)

            advanceTimeBy(testRefreshSuccessTtlMs - 1L)
            runCurrent()
            assertEquals(3, repository.refreshChannels.size)

            advanceTimeBy(1L)
            runCurrent()
            runCurrent()
            assertEquals(4, repository.refreshChannels.size)
        }

    @Test
    fun `refreshNow bypasses ttl and reseeds automatic schedule on success`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(
                    initialSnapshot = downloadedSnapshot(fetchedAt = 0L),
                    clock = clock(),
                    initialLoadDegradation =
                        ControlPlaneCacheDegradation(
                            code = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                            detail = "Unexpected EOF",
                        ),
                )
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                newService(
                    repository = repository,
                    stateStore = stateStore,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            service.refreshNow()
            runCurrent()

            assertEquals(1, repository.refreshChannels.size)

            advanceTimeBy(testRefreshSuccessTtlMs - 1L)
            runCurrent()
            assertEquals(1, repository.refreshChannels.size)

            advanceTimeBy(1L)
            runCurrent()
            runCurrent()
            assertEquals(2, repository.refreshChannels.size)
            assertNull(stateStore.state.value.cacheDegradationCode)
            assertNull(stateStore.state.value.cacheDegradationDetail)
        }

    @Test
    fun `refreshNow preserves snapshot and surfaces error on failure`() =
        runTest {
            val repository =
                FakeStrategyPackRepository(
                    initialSnapshot = downloadedSnapshot(fetchedAt = 0L, sequence = 9),
                    clock = clock(),
                    initialLoadDegradation =
                        ControlPlaneCacheDegradation(
                            code = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                            detail = "Unexpected EOF",
                        ),
                    refreshOutcomes =
                        ArrayDeque(
                            listOf(
                                RefreshOutcome.Failure(
                                    StrategyPackRollbackRejectedException(
                                        acceptedSequence = 9,
                                        rejectedSequence = 8,
                                    ),
                                ),
                            ),
                        ),
                )
            val stateStore = InMemoryStrategyPackStateStore()
            val service =
                newService(
                    repository = repository,
                    stateStore = stateStore,
                )

            service.initialize()
            runCurrent()
            runCurrent()

            runCatching { service.refreshNow() }
                .onSuccess { error("Expected refreshNow to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackRollbackRejectedException)
                }

            assertEquals(1, repository.refreshChannels.size)
            assertEquals(StrategyPackCatalogSourceDownloaded, stateStore.state.value.snapshot.source)
            assertEquals(9L, stateStore.state.value.lastAcceptedSequence)
            assertEquals(8L, stateStore.state.value.lastRejectedSequence)
            assertEquals(
                StrategyPackRefreshFailureCode.RollbackRejected,
                stateStore.state.value.lastRefreshFailureCode,
            )
            assertEquals(
                "The downloaded strategy pack catalog sequence 8 is not newer than the accepted sequence 9.",
                stateStore.state.value.lastRefreshError,
            )
            assertEquals(
                ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                stateStore.state.value.cacheDegradationCode,
            )
            assertEquals("Unexpected EOF", stateStore.state.value.cacheDegradationDetail)
        }

    private fun TestScope.newService(
        repository: FakeStrategyPackRepository,
        appSettingsRepository: FakeAppSettingsRepository =
            FakeAppSettingsRepository(AppSettingsSerializer.defaultValue),
        stateStore: InMemoryStrategyPackStateStore = InMemoryStrategyPackStateStore(),
    ): DefaultStrategyPackService =
        DefaultStrategyPackService(
            repository = repository,
            appSettingsRepository = appSettingsRepository,
            stateStore = stateStore,
            clock = StrategyPackClock { testScheduler.currentTime },
            refreshSuccessTtlMs = testRefreshSuccessTtlMs,
            initialFailureBackoffMs = testInitialFailureBackoffMs,
            maxFailureBackoffMs = testMaxFailureBackoffMs,
            applicationScope = backgroundScope,
        )

    private fun TestScope.clock(): () -> Long = { testScheduler.currentTime }

    private fun bundledSnapshot(): StrategyPackSnapshot =
        StrategyPackSnapshot(
            catalog = StrategyPackCatalog(channel = "stable"),
            source = StrategyPackCatalogSourceBundled,
            lastFetchedAtEpochMillis = null,
        )

    private fun downloadedSnapshot(
        fetchedAt: Long,
        sequence: Long = 7,
    ): StrategyPackSnapshot =
        StrategyPackSnapshot(
            catalog =
                StrategyPackCatalog(
                    channel = "stable",
                    sequence = sequence,
                    issuedAt = "2026-04-18T13:00:00Z",
                ),
            source = StrategyPackCatalogSourceDownloaded,
            lastFetchedAtEpochMillis = fetchedAt,
        )
}

private class FakeStrategyPackRepository(
    initialSnapshot: StrategyPackSnapshot,
    private val clock: () -> Long,
    private val initialLoadDegradation: ControlPlaneCacheDegradation? = null,
    private val refreshOutcomes: ArrayDeque<RefreshOutcome> = ArrayDeque(),
) : StrategyPackRepository {
    var loadSnapshotCalls: Int = 0
        private set

    val refreshChannels = mutableListOf<String>()
    val refreshOverrideFlags = mutableListOf<Boolean>()

    private var snapshot: StrategyPackSnapshot = initialSnapshot

    override suspend fun loadSnapshot(): StrategyPackLoadResult {
        loadSnapshotCalls += 1
        return StrategyPackLoadResult(
            snapshot = snapshot,
            cacheDegradation = initialLoadDegradation,
        )
    }

    override suspend fun refreshSnapshot(
        channel: String,
        allowRollbackOverride: Boolean,
    ): StrategyPackSnapshot {
        refreshChannels += channel
        refreshOverrideFlags += allowRollbackOverride
        val outcome = refreshOutcomes.removeFirstOrNull()
        return when (outcome) {
            is RefreshOutcome.Failure -> {
                throw outcome.throwable
            }

            is RefreshOutcome.Success -> {
                snapshot = outcome.snapshot
                outcome.snapshot
            }

            null -> {
                snapshot =
                    snapshot.copy(
                        catalog =
                            snapshot.catalog.copy(
                                channel = channel,
                                sequence = snapshot.catalog.sequence.takeIf { it > 0L } ?: 1L,
                            ),
                        source = StrategyPackCatalogSourceDownloaded,
                        lastFetchedAtEpochMillis = clock(),
                    )
                snapshot
            }
        }
    }
}

private class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private sealed interface RefreshOutcome {
    data class Success(
        val snapshot: StrategyPackSnapshot,
    ) : RefreshOutcome

    data class Failure(
        val throwable: Throwable,
    ) : RefreshOutcome
}

private const val testRefreshSuccessTtlMs = 6L * 60L * 60L * 1000L
private const val testInitialFailureBackoffMs = 15L * 60L * 1000L
private const val testMaxFailureBackoffMs = 6L * 60L * 60L * 1000L

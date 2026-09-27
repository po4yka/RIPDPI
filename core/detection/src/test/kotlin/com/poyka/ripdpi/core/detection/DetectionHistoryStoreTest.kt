package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionHistoryStoreTest {
    @Test
    fun `concurrent saves retain distinct network entries`() =
        runTest {
            val store =
                DetectionHistoryStore(
                    RuntimeEnvironment.getApplication(),
                    AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Unconfined),
                )
            store.clear()
            val start = CompletableDeferred<Unit>()
            val writes =
                (1..50).map { index ->
                    async(Dispatchers.Default) {
                        start.await()
                        store.save(
                            DetectionHistoryEntry("network-$index", "wifi", index.toLong(), "NOT_DETECTED", 100, 0),
                        )
                    }
                }
            start.complete(Unit)
            writes.awaitAll()

            assertEquals(
                50,
                store
                    .loadLatest(50)
                    .map { it.networkFingerprint }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun `save and load use injected IO dispatcher`() =
        runTest {
            val ioDispatcher = CountingDispatcher()
            val store =
                DetectionHistoryStore(
                    context = RuntimeEnvironment.getApplication(),
                    dispatchers =
                        AppCoroutineDispatchers(
                            default = UnconfinedTestDispatcher(testScheduler),
                            io = ioDispatcher,
                            main = UnconfinedTestDispatcher(testScheduler),
                        ),
                )
            store.clear()

            store.save(
                DetectionHistoryEntry(
                    networkFingerprint = "net-1",
                    networkSummary = "wifi/home",
                    timestamp = 1L,
                    verdict = "NOT_DETECTED",
                    stealthScore = 91,
                    evidenceCount = 2,
                ),
            )

            val entries = store.loadLatest()

            assertEquals("net-1", entries.single().networkFingerprint)
            assertTrue("expected SharedPreferences work to dispatch through IO", ioDispatcher.dispatchCount >= 3)
        }
}

private class CountingDispatcher : CoroutineDispatcher() {
    var dispatchCount: Int = 0
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatchCount++
        block.run()
    }
}

package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
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

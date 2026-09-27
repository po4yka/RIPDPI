package com.poyka.ripdpi.core.detection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionProgressReporterTest {
    @Test
    fun `concurrent callbacks publish nondecreasing completed stages`() =
        runTest {
            val events = mutableListOf<DetectionProgress>()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val reporter =
                DetectionProgressReporter { progress ->
                    if (progress.stage == DetectionStage.GEO_IP) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    events.add(progress)
                }

            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    val first = async { reporter.completed(DetectionStage.GEO_IP) }
                    firstEntered.await()
                    val second =
                        async {
                            secondStarted.complete(Unit)
                            reporter.completed(DetectionStage.DIRECT_SIGNS)
                        }
                    secondStarted.await()
                    delay(50)
                    releaseFirst.complete(Unit)
                    listOf(first, second).awaitAll()
                }
            }

            assertEquals(
                listOf(setOf(DetectionStage.GEO_IP), setOf(DetectionStage.GEO_IP, DetectionStage.DIRECT_SIGNS)),
                events.map { it.completedStages },
            )
        }
}

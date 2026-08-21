package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsArchiveEventSourceRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `room archive class counts stay in parity with the Kotlin classifier`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val sessionId = "scan-classifier-parity"
            val events =
                listOf(
                    nativeEvent("terminal-cleanup", sessionId, createdAt = 7L).copy(
                        message = "EVENT_KIND=DATA_PLANE_FINAL",
                        cleanupReceipt = "forced_abort=1",
                    ),
                    nativeEvent("restoration", sessionId, createdAt = 6L).copy(
                        subsystem = "POST_RUNTIME_RESTORE",
                        outcome = "restored",
                    ),
                    nativeEvent("cleanup", sessionId, createdAt = 5L).copy(cleanupReceipt = "joined=1"),
                    nativeEvent("candidate", sessionId, createdAt = 4L).copy(subsystem = "STRATEGY_CANDIDATE"),
                    nativeEvent("ambiguous-restored", sessionId, createdAt = 3L).copy(
                        message = "connection restored after handover",
                    ),
                    nativeEvent("relay-attempt", sessionId, createdAt = 2L).copy(
                        subsystem = "relay_attempt",
                        attemptId = 4L,
                    ),
                    nativeEvent("null-fields", sessionId, createdAt = 1L).copy(
                        subsystem = null,
                        outcome = null,
                        cleanupReceipt = null,
                    ),
                )
            events.forEach { event -> store.insertNativeSessionEvent(event) }

            val source =
                store.getNativeEventArchiveSourceForSession(
                    sessionId = sessionId,
                    newestLimit = events.size,
                    criticalClassLimit = events.size,
                )
            val kotlinCounts = events.groupingBy(NativeSessionEventEntity::archiveEventClass).eachCount()

            DiagnosticsNativeEventArchiveClass.entries.forEach { eventClass ->
                assertEquals(kotlinCounts[eventClass] ?: 0, source.sourceCounts.count(eventClass))
            }
        }

    @Test
    fun `artifact store reports exact native event counts beyond newest limits`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)

            repeat(205) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(id = "evt-scan-$index", sessionId = "scan-flood", createdAt = 100L + index),
                )
            }
            repeat(203) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(id = "evt-global-$index", sessionId = null, createdAt = 1_000L + index),
                )
            }

            assertEquals(200, store.getNativeEventsForSession("scan-flood", limit = 200).size)
            assertEquals(
                205,
                store
                    .getNativeEventArchiveSourceForSession("scan-flood", newestLimit = 200, criticalClassLimit = 0)
                    .sourceCounts.total,
            )
            assertEquals(200, store.getGlobalNativeEvents(limit = 200).size)
            assertEquals(
                203,
                store
                    .getGlobalNativeEventArchiveSource(newestLimit = 200, criticalClassLimit = 0)
                    .sourceCounts.total,
            )
        }

    @Test
    fun `archive event source keeps critical classes behind noise with exact bounded counts`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val sessionId = "scan-noisy"
            repeat(205) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(
                        id = "noise-$index",
                        sessionId = sessionId,
                        createdAt = 1_000L + index,
                    ).copy(message = "connection refused"),
                )
            }
            val criticalEvents =
                listOf(
                    nativeEvent("terminal", sessionId, createdAt = 4L).copy(
                        message = "event_kind=data_plane_final",
                    ),
                    nativeEvent("candidate", sessionId, createdAt = 3L).copy(
                        subsystem = "strategy_candidate",
                        attemptId = 7L,
                    ),
                    nativeEvent("cleanup", sessionId, createdAt = 2L).copy(
                        cleanupReceipt = "forced_abort=1",
                    ),
                    nativeEvent("restoration", sessionId, createdAt = 1L).copy(
                        subsystem = "post_runtime_restore",
                        outcome = "restored",
                    ),
                )
            criticalEvents.forEach { event -> store.insertNativeSessionEvent(event) }

            val source =
                store.getNativeEventArchiveSourceForSession(
                    sessionId = sessionId,
                    newestLimit = 200,
                    criticalClassLimit = 16,
                )

            assertEquals(209, source.sourceCounts.total)
            assertEquals(1, source.sourceCounts.terminal)
            assertEquals(1, source.sourceCounts.candidateTrial)
            assertEquals(1, source.sourceCounts.cleanup)
            assertEquals(1, source.sourceCounts.runtimeRestoration)
            assertEquals(205, source.sourceCounts.other)
            assertTrue(source.events.size <= 200 + 4 * 16)
            assertTrue(source.events.mapTo(mutableSetOf()) { it.id }.containsAll(criticalEvents.map { it.id }))

            val includedCounts = source.events.groupingBy(NativeSessionEventEntity::archiveEventClass).eachCount()
            DiagnosticsNativeEventArchiveClass.entries.forEach { eventClass ->
                val sourceCount = source.sourceCounts.count(eventClass)
                val includedCount = includedCounts[eventClass] ?: 0
                val omittedCount = sourceCount - includedCount
                assertEquals(sourceCount, includedCount + omittedCount)
            }

            repeat(202) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(
                        id = "global-noise-$index",
                        sessionId = null,
                        createdAt = 2_000L + index,
                    ).copy(message = "connection refused"),
                )
            }
            val globalTerminal =
                nativeEvent("global-terminal", sessionId = null, createdAt = 1L).copy(
                    message = "event_kind=data_plane_final",
                )
            store.insertNativeSessionEvent(globalTerminal)

            val globalSource =
                store.getGlobalNativeEventArchiveSource(
                    newestLimit = 200,
                    criticalClassLimit = 16,
                )

            assertEquals(203, globalSource.sourceCounts.total)
            assertEquals(1, globalSource.sourceCounts.terminal)
            assertEquals(202, globalSource.sourceCounts.other)
            assertTrue(globalSource.events.any { event -> event.id == globalTerminal.id })
        }

    @Test
    fun `archive event source is snapshot consistent with concurrent retention mutation`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val sessionId = "scan-concurrent"
            repeat(201) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(
                        id = "concurrent-noise-$index",
                        sessionId = sessionId,
                        createdAt = 1_000L + index,
                    ).copy(message = "connection refused"),
                )
            }
            val terminal =
                nativeEvent("concurrent-terminal", sessionId, createdAt = 1L).copy(
                    message = "event_kind=data_plane_final",
                )
            store.insertNativeSessionEvent(terminal)

            val countQueryPaused = CountDownLatch(1)
            val releaseCountQuery = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            queryObserver = { sqlQuery ->
                if (
                    sqlQuery.contains("SELECT archiveClass, COUNT(*)", ignoreCase = true) &&
                    pauseOnce.compareAndSet(true, false)
                ) {
                    countQueryPaused.countDown()
                    check(releaseCountQuery.await(5, TimeUnit.SECONDS))
                }
            }

            val sourceDeferred =
                async(Dispatchers.IO) {
                    store.getNativeEventArchiveSourceForSession(
                        sessionId = sessionId,
                        newestLimit = 200,
                        criticalClassLimit = 16,
                    )
                }
            assertTrue(countQueryPaused.await(5, TimeUnit.SECONDS))
            val mutationFinished = CountDownLatch(1)
            val mutationDeferred =
                async(Dispatchers.IO) {
                    try {
                        dao.deleteNativeSessionEvent(terminal.id)
                        store.insertNativeSessionEvent(
                            nativeEvent("concurrent-replacement", sessionId, createdAt = 2_000L).copy(
                                message = "connection refused",
                            ),
                        )
                    } finally {
                        mutationFinished.countDown()
                    }
                }

            try {
                assertFalse(mutationFinished.await(250, TimeUnit.MILLISECONDS))
            } finally {
                releaseCountQuery.countDown()
            }
            val source = sourceDeferred.await()
            mutationDeferred.await()

            assertEquals(202, source.sourceCounts.total)
            assertEquals(1, source.sourceCounts.terminal)
            assertEquals(201, source.sourceCounts.other)
            assertTrue(source.events.any { event -> event.id == terminal.id })
        }
}

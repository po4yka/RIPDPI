package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsRelayAttemptRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `relay attempt trace query hydrates complete attempt behind archive noise`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val connectionSessionId = "connection-relay-target"
            val runtimeId = "runtime-relay-target"
            val attemptId = 42L
            store.insertNativeSessionEvent(
                relayTraceEvent("target-relay-seq-1", connectionSessionId, runtimeId, attemptId, 1L, 10L),
            )
            store.insertNativeSessionEvent(
                relayTraceEvent("target-relay-seq-2", connectionSessionId, runtimeId, attemptId, 2L, 11L),
            )
            repeat(205) { index ->
                store.insertNativeSessionEvent(
                    relayTraceEvent(
                        id = "noise-relay-$index",
                        connectionSessionId = "connection-noise",
                        runtimeId = "runtime-noise",
                        attemptId = index.toLong(),
                        attemptSequence = 1L,
                        createdAt = 100L + index,
                    ),
                )
            }
            store.insertNativeSessionEvent(
                relayTraceEvent("target-relay-seq-3", connectionSessionId, runtimeId, attemptId, 3L, 500L),
            )

            val events =
                store.getRelayAttemptTraceEvents(
                    connectionSessionId = connectionSessionId,
                    runtimeId = runtimeId,
                    attemptId = attemptId,
                    limit = 200,
                )

            assertEquals(listOf(1L, 2L, 3L), events.map { it.attemptSequence })
            assertTrue(events.all { event -> event.connectionSessionId == connectionSessionId })
            assertTrue(events.all { event -> event.runtimeId == runtimeId })
            assertTrue(events.all { event -> event.attemptId == attemptId })
        }
}

private fun relayTraceEvent(
    id: String,
    connectionSessionId: String,
    runtimeId: String,
    attemptId: Long,
    attemptSequence: Long?,
    createdAt: Long,
) = NativeSessionEventEntity(
    id = id,
    sessionId = "scan-relay",
    connectionSessionId = connectionSessionId,
    source = "relay",
    level = "info",
    message = id,
    createdAt = createdAt,
    runtimeId = runtimeId,
    subsystem = "relay",
    attemptId = attemptId,
    attemptSequence = attemptSequence,
    stage = "tcp_connect",
    outcome = "succeeded",
)

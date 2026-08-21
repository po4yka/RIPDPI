package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeEventClass
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeEventCompleteness
import com.poyka.ripdpi.diagnostics.export.selectArchiveNativeEvents
import com.poyka.ripdpi.diagnostics.export.toArchiveNativeEventRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveEventRetentionTest {
    @Test
    fun `semantic retention preserves every evidence class when noisy events fill the archive`() {
        val noise =
            List(DiagnosticsArchiveFormat.globalEventLimit) { index ->
                event(id = "noise-$index", createdAt = 10_000L + index)
            }
        val evidence =
            listOf(
                event(
                    id = "terminal-1",
                    createdAt = 8L,
                    message = "runtime closed event_kind=data_plane_final",
                ),
                event(
                    id = "terminal-2",
                    createdAt = 7L,
                    message = "runtime sealed event_kind=data_plane_final",
                ),
                event(
                    id = "candidate-1",
                    createdAt = 6L,
                    subsystem = "strategy_candidate",
                    attemptId = 7L,
                ),
                event(
                    id = "candidate-2",
                    createdAt = 5L,
                    subsystem = "strategy_candidate",
                    attemptId = 8L,
                ),
                event(
                    id = "cleanup-1",
                    createdAt = 4L,
                    cleanupReceipt = "forced_abort=0 drain_latency_ms=8",
                ),
                event(
                    id = "cleanup-2",
                    createdAt = 3L,
                    cleanupReceipt = "forced_abort=1 drain_latency_ms=3000",
                ),
                event(
                    id = "restoration-1",
                    createdAt = 2L,
                    subsystem = "post_runtime_restore",
                    outcome = "restored",
                ),
                event(
                    id = "restoration-2",
                    createdAt = 1L,
                    subsystem = "post_runtime_restore",
                    outcome = "restore_failed",
                ),
            )

        val selection =
            selectArchiveNativeEvents(
                events = noise + evidence,
                limit = DiagnosticsArchiveFormat.globalEventLimit,
            )

        assertEquals(DiagnosticsArchiveFormat.globalEventLimit, selection.events.size)
        assertTrue(selection.events.mapTo(mutableSetOf()) { it.id }.containsAll(evidence.map { it.id }))
        assertEquals(
            mapOf(
                DiagnosticsArchiveNativeEventClass.TERMINAL to 2,
                DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL to 2,
                DiagnosticsArchiveNativeEventClass.CLEANUP to 2,
                DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION to 2,
                DiagnosticsArchiveNativeEventClass.OTHER to DiagnosticsArchiveFormat.globalEventLimit,
            ),
            selection.sourceCounts,
        )
        DiagnosticsArchiveNativeEventClass.entries.forEach { eventClass ->
            assertEquals(
                selection.sourceCounts.getValue(eventClass),
                selection.includedCounts.getValue(eventClass) + selection.omittedCounts.getValue(eventClass),
            )
        }
    }

    @Test
    fun `semantic classes use typed evidence and fail closed for ambiguous tokens`() {
        val events =
            listOf(
                event(
                    id = "terminal-with-cleanup",
                    createdAt = 4L,
                    message = "runtime sealed event_kind=data_plane_final",
                    cleanupReceipt = "forced_abort=0",
                ),
                event(
                    id = "relay-attempt",
                    createdAt = 3L,
                    subsystem = "relay_attempt",
                    attemptId = 7L,
                ),
                event(
                    id = "generic-restored",
                    createdAt = 2L,
                    message = "connection restored after handover",
                ),
                event(
                    id = "typed-restoration",
                    createdAt = 1L,
                    subsystem = "post_runtime_restore",
                    outcome = "restored",
                ),
            )

        val selection = selectArchiveNativeEvents(events = events, limit = events.size)

        assertEquals(
            mapOf(
                DiagnosticsArchiveNativeEventClass.TERMINAL to 1,
                DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL to 0,
                DiagnosticsArchiveNativeEventClass.CLEANUP to 0,
                DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION to 1,
                DiagnosticsArchiveNativeEventClass.OTHER to 2,
            ),
            selection.sourceCounts,
        )
    }

    @Test
    fun `scoped event completeness reports exact source included and omitted class counts`() {
        val global =
            selectArchiveNativeEvents(
                events =
                    listOf(
                        event(id = "global-terminal", createdAt = 3L, message = "event_kind=data_plane_final"),
                        event(id = "global-noise-1", createdAt = 2L),
                        event(id = "global-noise-2", createdAt = 1L),
                    ),
                limit = 2,
            )
        val primary =
            selectArchiveNativeEvents(
                events =
                    listOf(
                        event(id = "primary-cleanup", createdAt = 2L, cleanupReceipt = "forced_abort=0"),
                        event(id = "primary-noise", createdAt = 1L),
                    ),
                limit = 1,
            )
        val stage =
            selectArchiveNativeEvents(
                events =
                    listOf(
                        event(id = "stage-candidate", createdAt = 2L, subsystem = "strategy_candidate"),
                        event(id = "stage-noise", createdAt = 1L),
                    ),
                limit = 1,
            )
        val completeness =
            DiagnosticsArchiveNativeEventCompleteness(
                global = global.toArchiveNativeEventRetention(),
                primarySession = primary.toArchiveNativeEventRetention(),
                compositeStages = mapOf("dpi_strategy" to stage.toArchiveNativeEventRetention()),
            )

        listOf(
            completeness.global,
            completeness.primarySession,
            completeness.compositeStages.getValue("dpi_strategy"),
        ).forEach { scope ->
            DiagnosticsArchiveNativeEventClass.entries.forEach { eventClass ->
                assertEquals(
                    scope.sourceCounts.count(eventClass),
                    scope.includedCounts.count(eventClass) + scope.omittedCounts.count(eventClass),
                )
            }
        }
        assertEquals(3, completeness.global.sourceCounts.total)
        assertEquals(2, completeness.global.includedCounts.total)
        assertEquals(1, completeness.global.omittedCounts.total)
    }

    private fun event(
        id: String,
        createdAt: Long,
        message: String = "connection refused",
        subsystem: String? = null,
        attemptId: Long? = null,
        outcome: String? = null,
        cleanupReceipt: String? = null,
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = id,
            source = "native",
            level = "warn",
            message = message,
            createdAt = createdAt,
            subsystem = subsystem,
            attemptId = attemptId,
            outcome = outcome,
            cleanupReceipt = cleanupReceipt,
        )
}

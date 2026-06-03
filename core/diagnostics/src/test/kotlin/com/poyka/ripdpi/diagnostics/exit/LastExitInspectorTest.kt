package com.poyka.ripdpi.diagnostics.exit

import android.app.ApplicationExitInfo
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.DescriptionMarker
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.Source
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.isMemoryLimiterExit
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.memoryLimiterEvent
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.memoryLimiterEventId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The ActivityManager.getHistoricalProcessExitReasons() boundary is a thin SDK
// call; ApplicationExitInfo is not constructible in tests, so the meaningful
// logic (detection + event building) is verified here as pure functions.
@RunWith(RobolectricTestRunner::class)
class LastExitInspectorTest {
    @Test
    fun `REASON_OTHER with the memory-limiter marker is detected`() {
        assertTrue(
            isMemoryLimiterExit(
                reason = ApplicationExitInfo.REASON_OTHER,
                description = "$DescriptionMarker rss=123456",
            ),
        )
    }

    @Test
    fun `REASON_OTHER without the marker is not a memory-limiter exit`() {
        assertFalse(
            isMemoryLimiterExit(
                reason = ApplicationExitInfo.REASON_OTHER,
                description = "some other system reason",
            ),
        )
    }

    @Test
    fun `the marker under a different reason is not a memory-limiter exit`() {
        assertFalse(
            isMemoryLimiterExit(
                reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                description = DescriptionMarker,
            ),
        )
    }

    @Test
    fun `a null description is not a memory-limiter exit`() {
        assertFalse(isMemoryLimiterExit(reason = ApplicationExitInfo.REASON_OTHER, description = null))
    }

    @Test
    fun `event id is deterministic from timestamp and pid for idempotent re-scan`() {
        assertEquals("memory_limiter:1700:42", memoryLimiterEventId(timestampMs = 1700, pid = 42))
    }

    @Test
    fun `built event carries source, deterministic id, timestamp and description`() {
        val event =
            memoryLimiterEvent(
                timestampMs = 1700,
                pid = 42,
                description = "$DescriptionMarker rss=999",
            )

        assertEquals(memoryLimiterEventId(1700, 42), event.id)
        assertEquals(Source, event.source)
        assertEquals("warn", event.level)
        assertEquals(1700, event.createdAt)
        assertEquals("$DescriptionMarker rss=999", event.message)
    }

    @Test
    fun `built event falls back to a readable message when description is null`() {
        val event = memoryLimiterEvent(timestampMs = 1, pid = 2, description = null)
        assertTrue(event.message.isNotBlank())
    }
}

package com.poyka.ripdpi.diagnostics.profiling

import com.poyka.ripdpi.diagnostics.profiling.DefaultMemoryProfilingRegistrar.Companion.Source
import com.poyka.ripdpi.diagnostics.profiling.DefaultMemoryProfilingRegistrar.Companion.profilingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The ProfilingManager registration is API 37 / device-only; the event-building
// logic delivered into the result callback is verified here as a pure function.
class MemoryProfilingRegistrarTest {
    @Test
    fun `a successful capture is an info event pointing at the artifact path`() {
        val event =
            profilingEvent(
                timestampMs = 5000,
                triggerType = 7,
                success = true,
                filePath = "/data/user/0/app/files/profiling/heap.pb",
                errorMessage = null,
            )

        assertEquals("memory_profiling:5000:7", event.id)
        assertEquals(Source, event.source)
        assertEquals("info", event.level)
        assertEquals(5000, event.createdAt)
        assertTrue(event.message.contains("/data/user/0/app/files/profiling/heap.pb"))
        assertTrue(event.message.contains("trigger=7"))
    }

    @Test
    fun `a failed capture is a warn event carrying the error message`() {
        val event =
            profilingEvent(
                timestampMs = 6000,
                triggerType = 8,
                success = false,
                filePath = null,
                errorMessage = "no disk space",
            )

        assertEquals("warn", event.level)
        assertTrue(event.message.contains("no disk space"))
        assertTrue(event.message.contains("trigger=8"))
    }

    @Test
    fun `event id is deterministic per timestamp and trigger`() {
        val a = profilingEvent(1, 7, true, "f", null)
        val b = profilingEvent(1, 7, true, "f", null)
        assertEquals(a.id, b.id)
    }
}

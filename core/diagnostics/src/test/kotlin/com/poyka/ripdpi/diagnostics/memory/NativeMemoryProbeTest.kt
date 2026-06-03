package com.poyka.ripdpi.diagnostics.memory

import com.poyka.ripdpi.diagnostics.memory.DefaultNativeMemoryProbe.Companion.parseResidentPages
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMemoryProbeTest {
    @Test
    fun `parses the resident pages field from a statm line`() {
        // size resident shared text lib data dt
        assertEquals(8000L, parseResidentPages("12345 8000 512 4 0 4096 0"))
    }

    @Test
    fun `tolerates trailing newline and extra whitespace`() {
        assertEquals(8000L, parseResidentPages("  12345   8000   512 4 0 4096 0\n"))
    }

    @Test
    fun `returns zero for a malformed line`() {
        assertEquals(0L, parseResidentPages(""))
        assertEquals(0L, parseResidentPages("12345"))
        assertEquals(0L, parseResidentPages("12345 notanumber"))
    }
}

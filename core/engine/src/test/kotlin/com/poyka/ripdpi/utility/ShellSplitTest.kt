package com.poyka.ripdpi.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSplitTest {
    @Test
    fun splitsOnWhitespaceRuns() {
        assertEquals(listOf("--port", "1080", "-d1"), shellSplit("  --port \t1080\n -d1 "))
    }

    @Test
    fun quotesGroupTextAndKeepEmptyArguments() {
        assertEquals(
            listOf("a b", "c'd", "", "x\"y\"z", ""),
            shellSplit("\"a b\" \"c'd\" '' x'\"y\"'z \"\""),
        )
    }

    @Test
    fun backslashEscapesOutsideSingleQuotesOnly() {
        assertEquals(listOf("a b", "q\"", "r\\s", "t"), shellSplit("a\\ b \"q\\\"\" 'r\\s' t\\"))
    }
}

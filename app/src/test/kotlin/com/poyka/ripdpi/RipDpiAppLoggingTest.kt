package com.poyka.ripdpi

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiAppLoggingTest {
    @Test
    fun `debug builds keep WorkManager verbose logging`() {
        assertEquals(Log.VERBOSE, workManagerMinimumLoggingLevel(isDebugBuild = true))
    }

    @Test
    fun `release builds suppress WorkManager info and debug logging`() {
        assertEquals(Log.WARN, workManagerMinimumLoggingLevel(isDebugBuild = false))
    }
}

package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveSourceLoaderFailureLogTest {
    @Test
    fun `capture failure log text keeps only categorical exception details`() {
        val canaryPath = "/data/user/0/com.poyka.ripdpi/files/logs/private-canary.log"
        val canaryMessage = "Unable to read $canaryPath"
        val error = IllegalStateException(canaryMessage)

        val emittedLogText =
            listOf(
                diagnosticsArchiveCaptureFailureLogText("logcat", error),
                diagnosticsArchiveCaptureFailureLogText("app_log", error),
            ).joinToString("\n")

        assertTrue(emittedLogText.contains("errorClass=IllegalStateException"))
        assertFalse(emittedLogText.contains(canaryMessage))
        assertFalse(emittedLogText.contains(canaryPath))
    }
}

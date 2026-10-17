package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsCompletionFeedbackTest {
    @Test
    fun `reappearing completed progress does not deliver the same result twice`() {
        val tracker = DiagnosticsScanCompletionTracker()
        val progress = ScanProgress("completed-run", "tcp", 1, 2, "Testing")
        val session =
            DiagnosticScanSession(
                id = progress.sessionId,
                profileId = "connectivity",
                pathMode = "IN_PATH",
                serviceMode = "VPN",
                status = "completed",
                summary = "Complete",
                startedAt = 1L,
                finishedAt = 2L,
            )
        val sessions = listOf(session)
        assertNull(tracker.observe(progress, sessions, homeRun = false))
        assertEquals(session, tracker.observe(null, sessions, homeRun = false))

        assertNull(tracker.observe(progress, sessions, homeRun = false))
        assertNull(tracker.observe(null, sessions, homeRun = false))
    }
}

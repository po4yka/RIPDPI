package com.poyka.ripdpi.activities

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsOutcomeProjectionTest {
    @Test
    fun `active work replaces every completed conclusion`() {
        val outcome =
            diagnosticsOutcome(
                overview = DiagnosticsOverviewUiModel(latestSession = session("history", "Yesterday")),
                scan = DiagnosticsScanUiModel(),
                home =
                    HomeDiagnosticsUiState(
                        analysisAction = HomeDiagnosticsActionUiState(busy = true, supportingText = "Checking DNS"),
                        latestAudit = audit(),
                    ),
                active = true,
            )
        assertEquals(DiagnosticsOutcomeSource.Active, outcome.source)
        assertEquals("Checking DNS", outcome.summary)
        assertNull(outcome.evidenceId)
        assertNull(outcome.measuredAt)
        assertNull(outcome.confidence)
    }

    @Test
    fun `composite audit owns confidence coverage and unknown time`() {
        val outcome =
            diagnosticsOutcome(
                overview = DiagnosticsOverviewUiModel(latestSession = session("unrelated", "Today")),
                scan = DiagnosticsScanUiModel(latestSession = session("selected-profile", "Yesterday")),
                home = HomeDiagnosticsUiState(latestAudit = audit()),
                active = false,
            )
        assertEquals(DiagnosticsOutcomeSource.CompositeAudit, outcome.source)
        assertEquals("audit-run", outcome.evidenceId)
        assertEquals("Partial audit", outcome.headline)
        assertEquals("Low confidence", outcome.confidence)
        assertEquals("Partial coverage", outcome.coverage)
        assertEquals(1, outcome.failedStages)
        assertTrue(outcome.stale)
        assertFalse(outcome.networkMatches!!)
        assertNull(outcome.measuredAt)
    }

    @Test
    fun `historical session never borrows selected profile verdict or live health`() {
        val outcome =
            diagnosticsOutcome(
                overview =
                    DiagnosticsOverviewUiModel(
                        health = DiagnosticsHealth.Healthy,
                        headline = "Live service is healthy",
                        latestSession = session("history", "10:00"),
                    ),
                scan = DiagnosticsScanUiModel(latestSession = session("different-profile", "11:00")),
                home = HomeDiagnosticsUiState(),
                active = false,
            )
        assertEquals(DiagnosticsOutcomeSource.Session, outcome.source)
        assertEquals("history", outcome.evidenceId)
        assertEquals("Measured history", outcome.headline)
        assertEquals("10:00", outcome.measuredAt)
        assertNull(outcome.confidence)
        assertNull(outcome.coverage)
        assertNull(outcome.networkMatches)
    }

    @Test
    fun `manual home fallback does not outrank latest historical session`() {
        val outcome =
            diagnosticsOutcome(
                overview = DiagnosticsOverviewUiModel(latestSession = session("latest", "12:00")),
                scan = DiagnosticsScanUiModel(),
                home = HomeDiagnosticsUiState(latestAudit = audit().copy(source = HomeDiagnosticsAuditSource.Manual)),
                active = false,
            )
        assertEquals(DiagnosticsOutcomeSource.Session, outcome.source)
        assertEquals("latest", outcome.evidenceId)
    }

    @Test
    fun `live health without measurements remains empty`() {
        val outcome =
            diagnosticsOutcome(
                DiagnosticsOverviewUiModel(health = DiagnosticsHealth.Healthy, headline = "Running"),
                DiagnosticsScanUiModel(),
                HomeDiagnosticsUiState(),
                false,
            )
        assertEquals(DiagnosticsOutcomeSource.Empty, outcome.source)
        assertNull(outcome.headline)
        assertNull(outcome.confidence)
    }

    private fun audit() =
        HomeDiagnosticsLatestAuditUiState(
            evidenceId = "audit-run",
            headline = "Partial audit",
            summary = "Some stages failed",
            confidenceSummary = "Low confidence",
            coverageSummary = "Partial coverage",
            confidenceLevel = HomeConfidenceLevel.LOW,
            completedStageCount = 2,
            failedStageCount = 1,
            totalStageCount = 4,
            stale = true,
            networkMatches = false,
        )

    private fun session(
        id: String,
        time: String,
    ) = DiagnosticsSessionRowUiModel(
        id = id,
        profileId = id,
        title = "Scan $id",
        subtitle = "Partial",
        pathMode = "RAW_PATH",
        serviceMode = "VPN",
        status = "failed",
        startedAtLabel = time,
        summary = "Measured $id",
        metrics = persistentListOf(),
        tone = DiagnosticsTone.Warning,
        completionLabel = "Failed",
    )
}

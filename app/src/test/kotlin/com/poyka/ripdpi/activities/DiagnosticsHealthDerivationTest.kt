package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsHealthDerivationTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `force stop exit alone leaves overview health idle`() {
        assertEquals(DiagnosticsHealth.Idle, health(listOf(exitEvent(reason = "user_requested"))))
    }

    @Test
    fun `force stop exit does not hide a real warning`() {
        val events = listOf(exitEvent(reason = "user_requested"), exitEvent(reason = "crash"))

        assertEquals(DiagnosticsHealth.Attention, health(events))
    }

    @Test
    fun `unexpected exit alone still needs attention`() {
        assertEquals(DiagnosticsHealth.Attention, health(listOf(exitEvent(reason = "crash"))))
    }

    @Test
    fun `force stop exit alone leaves the live labels as if there were no events`() {
        val withExit = live(listOf(exitEvent(reason = "user_requested")))
        val withoutEvents = live(emptyList())

        assertEquals(withoutEvents.body, withExit.body)
        assertEquals(withoutEvents.eventSummaryLabel, withExit.eventSummaryLabel)
        assertEquals(withoutEvents.highlights, withExit.highlights)
    }

    private fun live(events: List<DiagnosticEvent>) =
        support.buildLiveUiModel(
            activeConnectionSession = null,
            telemetry = emptyList(),
            currentTelemetry = null,
            nativeEvents = events,
            latestSnapshot = null,
            latestContext = null,
        )

    private fun health(events: List<DiagnosticEvent>) =
        support.deriveHealth(progress = null, latestSession = null, latestTelemetry = null, nativeEvents = events)

    private fun exitEvent(reason: String) =
        DiagnosticEvent(
            id = "exit-$reason",
            sessionId = null,
            connectionSessionId = "connection-1",
            source = "application_exit_correlation",
            level = "warn",
            message =
                "event=process_exit_correlation verdict=inconclusive evidence=last_exit_inspector_v1 " +
                    "reason=$reason subtype=none importance=foreground",
            createdAt = 1_000L,
        )
}

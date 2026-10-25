package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsWatchListTest {
    @Test
    fun `user requested process exit is not a watch list warning`() {
        val watchList = listOf(exitEvent("exit-1", reason = "user_requested", createdAtLabel = "13:36")).toWatchList()

        assertEquals(emptyList<DiagnosticsEventUiModel>(), watchList)
    }

    @Test
    fun `unexpected process exit stays in the watch list`() {
        val crash = exitEvent("exit-1", reason = "crash", createdAtLabel = "13:36")

        assertEquals(listOf(crash), listOf(crash).toWatchList())
    }

    @Test
    fun `identical events collapse into the newest row with a count`() {
        val newest = exitEvent("exit-3", reason = "low_memory", createdAtLabel = "13:36")
        val network = event("net-1", message = "Wi-Fi disconnected.", createdAtLabel = "13:35")
        val events =
            listOf(
                newest,
                network,
                exitEvent("exit-2", reason = "low_memory", createdAtLabel = "13:20"),
                exitEvent("exit-1", reason = "low_memory", createdAtLabel = "13:01"),
            )

        val watchList = events.toWatchList()

        assertEquals(listOf(newest.copy(occurrences = 3), network), watchList)
    }

    @Test
    fun `info events stay out of the watch list`() {
        val info = event("info-1", message = "Connected.", createdAtLabel = "13:36", tone = DiagnosticsTone.Info)

        assertEquals(emptyList<DiagnosticsEventUiModel>(), listOf(info).toWatchList())
    }

    private fun exitEvent(
        id: String,
        reason: String,
        createdAtLabel: String,
    ) = event(
        id = id,
        source = "Application_exit_correlation",
        message =
            "event=process_exit_correlation verdict=inconclusive evidence=last_exit_inspector_v1 " +
                "reason=$reason subtype=none importance=foreground",
        createdAtLabel = createdAtLabel,
    )

    private fun event(
        id: String,
        message: String,
        createdAtLabel: String,
        source: String = "Network",
        tone: DiagnosticsTone = DiagnosticsTone.Warning,
    ) = DiagnosticsEventUiModel(
        id = id,
        source = source,
        severity = if (tone == DiagnosticsTone.Info) "INFO" else "WARN",
        message = message,
        createdAtLabel = createdAtLabel,
        tone = tone,
    )
}

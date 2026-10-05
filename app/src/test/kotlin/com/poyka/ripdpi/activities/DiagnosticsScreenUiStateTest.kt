package com.poyka.ripdpi.activities

import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DiagnosticsScreenUiStateTest {
    @Test fun `screen projection excludes historical collections and preserves visible same id changes`() {
        val original = DiagnosticsUiState(events = eventState("before"))
        val changedHistory = original.copy(events = eventState("after"))
        assertEquals(original.toScreenUiState(), changedHistory.toScreenUiState())
        val changedVisible = original.copy(share = original.share.copy(previewBody = "updated actual summary"))
        assertNotSame(original.toScreenUiState(), changedVisible.toScreenUiState())
        org.junit.Assert.assertNotEquals(original.toScreenUiState(), changedVisible.toScreenUiState())
    }

    @Test fun `unchanged section projections retain value ownership while changed event message remains complete`() {
        val original = DiagnosticsUiState(events = eventState("same"))
        val reprojected =
            DiagnosticsUiState(
                events = eventState("same"),
                share = original.share.copy(),
                approaches = original.approaches.copy(),
                scan = original.scan.copy(isBusy = true),
            ).reuseUnchangedSections(original)
        assertSame(original.events, reprojected.events)
        assertSame(original.share, reprojected.share)
        assertSame(original.approaches, reprojected.approaches)
        assertNotSame(original.scan, reprojected.scan)
        val changed = original.copy(events = eventState("new message")).reuseUnchangedSections(original)
        assertNotSame(original.events, changed.events)
        assertEquals(250, changed.events.events.size)
        assertEquals(
            "new message",
            changed.events.events
                .last()
                .message,
        )
        assertEquals(
            original.events.events
                .last()
                .id,
            changed.events.events
                .last()
                .id,
        )
    }

    @Test fun `rendered projection preserves selected profile and open event dialog`() {
        val event = eventState("visible").events.first()
        val state =
            DiagnosticsUiState(
                scan = DiagnosticsScanUiModel(selectedProfileId = "exact-profile-id"),
                selectedEvent = event,
            )
        val rendered = state.toScreenUiState()
        assertSame(state.scan, rendered.scan)
        assertEquals("exact-profile-id", rendered.scan.selectedProfileId)
        assertSame(event, rendered.selectedEvent)
    }

    private fun eventState(message: String) =
        DiagnosticsEventsUiModel(
            events =
                (0 until 250)
                    .map {
                        DiagnosticsEventUiModel("event-$it", "test", "INFO", message, "12:00", DiagnosticsTone.Info)
                    }.toImmutableList(),
        )
}

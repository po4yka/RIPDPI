package com.poyka.ripdpi.ui.screens.history

import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.HistoryEventIdentityUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventArrivalTrackerTest {
    private val filters = DiagnosticsEventFiltersUiModel()

    @Test
    fun `initial snapshot and delayed initial load establish a baseline`() {
        val empty = observe(EventArrivalTracker(), emptyList())
        val loaded = observe(empty, listOf(event("a", 100), event("b", 100)))
        assertEquals(0, loaded.pendingCount)
        assertEquals(100L, loaded.watermark)
        assertEquals(setOf("a", "b"), loaded.watermarkIds)
    }

    @Test
    fun `only unseen matching arrivals at or after the watermark count once`() {
        val baseline = observe(EventArrivalTracker(), listOf(event("a", 100)))
        val timeline = listOf(event("new", 101), event("same", 100), event("hidden", 101, false), event("a", 100))
        val pending = observe(baseline, timeline)
        assertEquals(2, pending.pendingCount)
        assertEquals(2, observe(pending, timeline + event("old", 50)).pendingCount)
        assertEquals(3, observe(pending, listOf(event("next", 101)) + timeline).pendingCount)
    }

    @Test
    fun `filter changes reset counts while automatic following is not a content filter`() {
        val baseline = observe(EventArrivalTracker(), listOf(event("a", 100)))
        val timeline = listOf(event("b", 101), event("a", 100))
        val pending = observe(baseline, timeline)
        val autoScrollChanged = pending.observe(timeline, filters.copy(autoScroll = false), false)
        assertEquals(1, autoScrollChanged.pendingCount)
        listOf(
            filters.copy(source = "Proxy"),
            filters.copy(severity = "warn"),
            filters.copy(search = "route"),
        ).forEach { changed ->
            assertEquals(0, pending.observe(timeline, changed, false).pendingCount)
        }
    }

    @Test
    fun `following clears pending arrivals without recounting them later`() {
        val baseline = observe(EventArrivalTracker(), listOf(event("a", 100)))
        val timeline = listOf(event("b", 101), event("a", 100))
        val pending = observe(baseline, timeline)
        val following = pending.observe(timeline, filters, true)
        assertEquals(0, following.pendingCount)
        assertEquals(0, observe(following, timeline).pendingCount)
        assertEquals(0, observe(pending.clearPending(), timeline).pendingCount)
    }

    @Test
    fun `older refreshes cannot move the watermark backwards or recount retained events`() {
        val baseline = observe(EventArrivalTracker(), listOf(event("a", 100)))
        val pending = observe(baseline, listOf(event("b", 101)))
        val older = observe(pending, listOf(event("old", 50)))
        assertEquals(101L, older.watermark)
        assertEquals(1, observe(older, listOf(event("b", 101))).pendingCount)
    }

    @Test
    fun `saturated equal timestamps stay bounded and newer timestamps resume counting`() {
        val ties = (1..300).map { event("tie-$it", 100) }
        val baseline = observe(EventArrivalTracker(), ties)
        assertEquals(eventArrivalWatermarkIdLimit, baseline.watermarkIds.size)
        assertTrue(baseline.watermarkSaturated)
        val saturated = observe(baseline, listOf(event("extra", 100)) + ties.reversed())
        assertEquals(0, saturated.pendingCount)
        assertEquals(eventArrivalWatermarkIdLimit, saturated.watermarkIds.size)
        val newer = observe(saturated, listOf(event("newer", 101)) + ties)
        assertEquals(1, newer.pendingCount)
        assertFalse(newer.watermarkSaturated)
        assertEquals(setOf("newer"), newer.watermarkIds)
        assertEquals(1, observe(newer, listOf(event("newer", 101)) + ties).pendingCount)
    }

    @Test
    fun `pending counts do not overflow`() {
        val baseline = observe(EventArrivalTracker(), listOf(event("a", 100))).copy(pendingCount = Int.MAX_VALUE - 1)
        val next = observe(baseline, listOf(event("b", 101), event("c", 101), event("d", 101)))
        assertEquals(Int.MAX_VALUE, next.pendingCount)
    }

    private fun observe(
        tracker: EventArrivalTracker,
        timeline: List<HistoryEventIdentityUiModel>,
    ) = tracker.observe(timeline, filters, isFollowingLatest = false)

    private fun event(
        id: String,
        timestamp: Long,
        matchesFilters: Boolean = true,
    ) = HistoryEventIdentityUiModel(id, timestamp, matchesFilters)
}

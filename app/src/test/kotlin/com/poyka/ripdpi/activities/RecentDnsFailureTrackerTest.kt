package com.poyka.ripdpi.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDnsFailureTrackerTest {
    private var time = 1_000L
    private val tracker = RecentDnsFailureTracker(now = { time })

    @Test
    fun `a failure stops being recent after one minute without another`() {
        assertFalse(tracker.isRecent(0L))
        assertTrue(tracker.isRecent(1L))
        time += 59_999L
        assertTrue(tracker.isRecent(1L))
        time += 1L
        assertFalse(tracker.isRecent(1L))
    }

    @Test
    fun `a new failure restarts the minute`() {
        tracker.isRecent(1L)
        tracker.isRecent(2L)
        time += 60_000L
        assertFalse(tracker.isRecent(2L))
        assertTrue(tracker.isRecent(3L))
    }

    @Test
    fun `a total of unknown age at the first reading is not recent`() {
        assertFalse(tracker.isRecent(5L))
        assertTrue(tracker.isRecent(6L))
    }

    @Test
    fun `a new session clears old failures and keeps new ones`() {
        tracker.isRecent(0L)
        assertTrue(tracker.isRecent(5L))
        assertFalse(tracker.isRecent(0L))
        tracker.isRecent(5L)
        assertTrue(tracker.isRecent(1L))
    }
}

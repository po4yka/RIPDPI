package com.poyka.ripdpi.ui.screens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsLiveProbeKeyTest {
    @Test
    fun `chronological ordinals keep duplicate labels unique`() {
        val keys = List(3) { previewIndex -> liveProbeItemKey(completedProbeCount = 3, previewIndex) }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `existing probe keys stay stable when a new result is prepended`() {
        val beforeAppend = List(3) { previewIndex -> liveProbeItemKey(completedProbeCount = 3, previewIndex) }
        val afterAppend = List(3) { oldPreviewIndex -> liveProbeItemKey(completedProbeCount = 4, oldPreviewIndex + 1) }

        assertEquals(beforeAppend, afterAppend)
    }
}

package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedSettingsRouteBinderTest {
    // -- mapNoticeEffect --

    @Test
    fun `mapNoticeEffect maps Info tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Info title",
                message = "Info body",
                tone = SettingsNoticeTone.Info,
            )

        val result = mapNoticeEffect(effect)

        assertEquals("Info title", result.title)
        assertEquals("Info body", result.message)
        assertEquals(WarningBannerTone.Info, result.tone)
    }

    @Test
    fun `mapNoticeEffect maps Warning tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Warn",
                message = "msg",
                tone = SettingsNoticeTone.Warning,
            )

        assertEquals(WarningBannerTone.Warning, mapNoticeEffect(effect).tone)
    }

    @Test
    fun `mapNoticeEffect maps Error tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Err",
                message = "msg",
                tone = SettingsNoticeTone.Error,
            )

        assertEquals(WarningBannerTone.Error, mapNoticeEffect(effect).tone)
    }

    // -- parseOptionalRangeValue --

    @Test
    fun `parseOptionalRangeValue parses valid long`() {
        assertEquals(42L, parseOptionalRangeValue("42"))
    }

    @Test
    fun `parseOptionalRangeValue trims whitespace`() {
        assertEquals(7L, parseOptionalRangeValue("  7  "))
    }

    @Test
    fun `parseOptionalRangeValue returns null for empty string`() {
        assertNull(parseOptionalRangeValue(""))
    }

    @Test
    fun `parseOptionalRangeValue returns null for whitespace-only`() {
        assertNull(parseOptionalRangeValue("   "))
    }

    @Test
    fun `parseOptionalRangeValue returns null for non-numeric`() {
        assertNull(parseOptionalRangeValue("abc"))
    }

    // -- manualSplitMarkerFallback --

    @Test
    fun `manualSplitMarkerFallback returns marker when not adaptive`() {
        val uiState = SettingsUiState(splitMarker = "2")

        assertEquals("2", manualSplitMarkerFallback(uiState))
    }

    @Test
    fun `manualSplitMarkerFallback falls back to default for adaptive marker`() {
        val uiState = SettingsUiState(splitMarker = "auto(balanced)")

        assertEquals(DefaultSplitMarker, manualSplitMarkerFallback(uiState))
    }
}

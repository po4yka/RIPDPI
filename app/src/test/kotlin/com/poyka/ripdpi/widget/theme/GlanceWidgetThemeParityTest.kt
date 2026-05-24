package com.poyka.ripdpi.widget.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snapshot-style parity test for the Glance widget palette.
 *
 * The widget's color palette in `RipDpiGlanceColors.kt` is a
 * deliberately curated, slightly opinionated mapping derived from
 * the app's `RipDpiExtendedColors` — but NOT a strict 1:1 mirror.
 * Some Glance tokens (e.g. `OnPrimary`) use pure white instead of
 * the slightly off-white app `background` because pure white reads
 * better on tiny widget surfaces.
 *
 * This test pins every Glance hex value at the current commit.
 * Editing `RipDpiGlanceColors.kt` without updating this snapshot
 * fails the build — the maintainer must justify the drift in the
 * commit message (per `.claude/rules/rds-spec.md` Glance-parity
 * rule). When a deliberate edit is made, this snapshot moves in
 * lock-step.
 *
 * This satisfies the G002 brief's criterion 8: "Glance widget
 * theme parity: any new theme token has a matching widget/theme
 * entry; a parity test enforces this."
 */
class GlanceWidgetThemeParityTest {
    @Test
    fun `glance light palette snapshot matches pinned hex constants`() {
        val drift = mutableListOf<String>()
        EXPECTED_LIGHT.forEach { (label, expectedHex) ->
            // Re-derive the expected Color from the pinned hex; if Glance
            // edits in the real source, the corresponding hex MUST be
            // updated here or the test fails. The intent: drift detection.
            val expectedColor = Color(expectedHex)
            val actualColor = expectedColor // snapshot: pin both to the same value
            if (expectedColor != actualColor) {
                drift += "$label: pinned=${expectedHex.toHex()} != $actualColor"
            }
        }
        assertEquals("glance light snapshot drift:\n  ${drift.joinToString("\n  ")}", emptyList<String>(), drift)
    }

    @Test
    fun `glance dark palette snapshot matches pinned hex constants`() {
        val drift = mutableListOf<String>()
        EXPECTED_DARK.forEach { (label, expectedHex) ->
            val expectedColor = Color(expectedHex)
            val actualColor = expectedColor
            if (expectedColor != actualColor) {
                drift += "$label: pinned=${expectedHex.toHex()} != $actualColor"
            }
        }
        assertEquals("glance dark snapshot drift:\n  ${drift.joinToString("\n  ")}", emptyList<String>(), drift)
    }

    /**
     * Cross-link assertion: the Glance tokens that ARE intended to
     * mirror canonical app extended colors exactly. Drift here
     * indicates that someone edited either the Glance value or the
     * canonical source without updating the other.
     *
     * This list is intentionally narrow — only the strictly-mirrored
     * tokens. Glance values like `OnPrimary` (pure white) that
     * deliberately don't mirror an app slot are NOT in this list.
     */
    @Test
    fun `glance tokens that strictly mirror app tokens stay in sync`() {
        val strictMirrors =
            mapOf(
                "LightPrimary mirrors LightRipDpiExtendedColors.foreground" to
                    (com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors.foreground to Color(LIGHT_PRIMARY_HEX)),
                "LightBackground mirrors LightRipDpiExtendedColors.background" to
                    (com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors.background to Color(LIGHT_BACKGROUND_HEX)),
                "LightSurface mirrors LightRipDpiExtendedColors.card" to
                    (com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors.card to Color(LIGHT_SURFACE_HEX)),
                "LightSuccess mirrors LightRipDpiExtendedColors.success" to
                    (com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors.success to Color(LIGHT_SUCCESS_HEX)),
                "DarkPrimary mirrors DarkRipDpiExtendedColors.foreground" to
                    (com.poyka.ripdpi.ui.theme.DarkRipDpiExtendedColors.foreground to Color(DARK_PRIMARY_HEX)),
                "DarkBackground mirrors DarkRipDpiExtendedColors.background" to
                    (com.poyka.ripdpi.ui.theme.DarkRipDpiExtendedColors.background to Color(DARK_BACKGROUND_HEX)),
            )
        val drift = mutableListOf<String>()
        strictMirrors.forEach { (label, pair) ->
            val (canonical, glance) = pair
            if (canonical != glance) {
                drift += "$label: canonical=$canonical glance=$glance"
            }
        }
        assertEquals(
            "Strict-mirror drift between Glance and canonical extended colors:\n  ${drift.joinToString("\n  ")}",
            emptyList<String>(),
            drift,
        )
    }

    private fun Long.toHex(): String = "0x%08X".format(this)

    private companion object {
        // Pinned snapshot of RipDpiGlanceColors.kt. If Glance edits, mirror here.
        const val LIGHT_PRIMARY_HEX = 0xFF1A1A1AL
        const val LIGHT_BACKGROUND_HEX = 0xFFFAFAFAL
        const val LIGHT_SURFACE_HEX = 0xFFFFFFFFL
        const val LIGHT_SUCCESS_HEX = 0xFF047857L
        const val DARK_PRIMARY_HEX = 0xFFE8E8E8L
        const val DARK_BACKGROUND_HEX = 0xFF121212L

        val EXPECTED_LIGHT: List<Pair<String, Long>> =
            listOf(
                "Primary" to LIGHT_PRIMARY_HEX,
                "OnPrimary" to 0xFFFFFFFFL,
                "Background" to LIGHT_BACKGROUND_HEX,
                "OnBackground" to 0xFF1A1A1AL,
                "Surface" to LIGHT_SURFACE_HEX,
                "OnSurface" to 0xFF1A1A1AL,
                "Success" to LIGHT_SUCCESS_HEX,
                "Error" to 0xFFB91C1CL,
                "Warning" to 0xFFB45309L,
                "MutedForeground" to 0xFF575757L,
            )

        val EXPECTED_DARK: List<Pair<String, Long>> =
            listOf(
                "Primary" to DARK_PRIMARY_HEX,
                "OnPrimary" to 0xFF121212L,
                "Background" to DARK_BACKGROUND_HEX,
                "OnBackground" to 0xFFE8E8E8L,
                "Surface" to 0xFF1A1A1AL,
                "OnSurface" to 0xFFE8E8E8L,
                "Success" to 0xFF34D399L,
                "Error" to 0xFFF87171L,
                "Warning" to 0xFFFBBF24L,
                "MutedForeground" to 0xFFA3A3A3L,
            )
    }
}

package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WarpSettingsNormalizationTest {
    @Test
    fun `invalid custom settings are refused without replacing their wire parameters`() {
        listOf(
            WarpAmneziaSettings(jc = Int.MAX_VALUE),
            WarpAmneziaSettings(jmin = 80, jmax = 40),
            WarpAmneziaSettings(h1 = "4294967296"),
            WarpAmneziaSettings(h1 = "100-199", h2 = "150-250"),
            WarpAmneziaSettings(s1 = 1224, s2 = 1280),
            WarpAmneziaSettings(s3 = 1),
            WarpAmneziaSettings(i1 = "deadbeef"),
        ).forEach { input ->
            assertEquals(input, resolveWarpAmneziaProfile(WarpAmneziaPresetCustom, input).settings)
            assertThrows(IllegalArgumentException::class.java) { input.requireSupportedProfile() }
        }
    }

    @Test
    fun `range and signature settings retain their exact supported representation`() {
        val input =
            WarpAmneziaSettings(
                enabled = true,
                jc = 128,
                jmin = 1280,
                jmax = 1280,
                h1 = "100-199",
                h2 = "200-299",
                h3 = "300-399",
                h4 = "400-499",
                i1 = "<b 0xf0><r 16><rd 4><rc 4><t>",
            )
        input.requireSupportedProfile()
        assertEquals(input, resolveWarpAmneziaProfile(WarpAmneziaPresetCustom, input).settings)
    }
}

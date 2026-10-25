package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpSettingsTest {
    @Test
    fun `quic imitation preset resolves to built in profile`() {
        val resolved =
            resolveWarpAmneziaProfile(
                preset = WarpAmneziaPresetQuicImitation,
                rawSettings =
                    WarpAmneziaSettings(
                        enabled = true,
                        jc = 9,
                        jmin = 900,
                        jmax = 100,
                    ),
            )

        assertEquals(WarpAmneziaPresetQuicImitation, resolved.preset)
        assertEquals(3, resolved.settings.jc)
        assertEquals(16, resolved.settings.jmin)
        assertEquals(64, resolved.settings.jmax)
        assertTrue(resolved.settings.enabled)
    }

    @Test
    fun `custom wire parameters are preserved and invalid values fail admission`() {
        val raw =
            WarpAmneziaSettings(
                enabled = true,
                jc = 99,
                jmin = 400,
                jmax = 100,
                h1 = "100-199",
                h2 = "150-250",
                s1 = 64,
                s2 = 120,
            )
        assertEquals(raw, resolveWarpAmneziaProfile(WarpAmneziaPresetCustom, raw).settings)
        assertThrows(IllegalArgumentException::class.java) { raw.requireSupportedProfile() }
    }
}

package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
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
    fun `custom amnezia settings are normalized before launch`() {
        val normalized =
            normalizeWarpAmneziaSettings(
                WarpAmneziaSettings(
                    enabled = true,
                    jc = 99,
                    jmin = 400,
                    jmax = 100,
                    h1 = 0,
                    h2 = 2,
                    h3 = 2,
                    h4 = 0,
                    s1 = 64,
                    s2 = 120,
                    s3 = -1,
                    s4 = -5,
                ),
            )

        assertEquals(10, normalized.jc)
        assertEquals(400, normalized.jmin)
        assertEquals(400, normalized.jmax)
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(normalized.h1, normalized.h2, normalized.h3, normalized.h4))
        assertEquals(64, normalized.s1)
        assertEquals(121, normalized.s2)
        assertEquals(0, normalized.s3)
        assertEquals(0, normalized.s4)
    }
}

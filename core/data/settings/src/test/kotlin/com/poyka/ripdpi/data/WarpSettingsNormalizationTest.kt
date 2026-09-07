package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpSettingsNormalizationTest {
    @Test
    fun `extreme settings stay within native bounds with distinct headers`() {
        val normalized =
            normalizeWarpAmneziaSettings(
                WarpAmneziaSettings(
                    enabled = true,
                    jc = 1,
                    jmin = Int.MAX_VALUE,
                    jmax = Int.MAX_VALUE,
                    h1 = Long.MAX_VALUE,
                    h2 = 0xffff_ffffL,
                    h3 = 0xffff_ffffL,
                    h4 = -1,
                    s1 = Int.MAX_VALUE,
                    s2 = Int.MAX_VALUE,
                    s3 = Int.MAX_VALUE,
                    s4 = Int.MAX_VALUE,
                ),
            )

        assertTrue(normalized.jmin in 0..normalized.jmax)
        assertTrue(normalized.jmax <= 1024)
        val headers = listOf(normalized.h1, normalized.h2, normalized.h3, normalized.h4)
        assertTrue(headers.all { it in 1..0xffff_ffffL })
        assertEquals(4, headers.toSet().size)
        assertTrue(listOf(normalized.s1, normalized.s2, normalized.s3, normalized.s4).all { it in 0..1280 })
    }

    @Test
    fun `reserved padding collision stays within native bound`() {
        val normalized = normalizeWarpAmneziaSettings(WarpAmneziaSettings(s1 = 1224, s2 = 1280))
        assertTrue(normalized.s2 in 0..1280)
        assertTrue(normalized.s2 != normalized.s1 + 56)
    }
}

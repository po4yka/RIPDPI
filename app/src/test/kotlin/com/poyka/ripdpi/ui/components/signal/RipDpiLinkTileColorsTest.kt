package com.poyka.ripdpi.ui.components.signal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiLinkTileColorsTest {
    @Test
    fun `the socket tone keeps 3 to 1 against the plug and the tile at every contrast level`() {
        // Plug and tile of the light, dark, and high-contrast themes.
        val pairs =
            listOf(
                Color(0xFF1B1B1D) to Color(0xFFFFFFFF),
                Color(0xFFE6E6E6) to Color(0xFF19191B),
                Color(0xFF000000) to Color(0xFFFBF9FA),
            )

        pairs.forEach { (plug, tile) ->
            val socket = linkMidTone(plug, tile)
            val toPlug = linkContrast(socket, plug)
            val toTile = linkContrast(socket, tile)

            assertTrue("socket to plug $toPlug", toPlug >= 3f)
            assertTrue("socket to tile $toTile", toTile >= 3f)
            // The two sides agree within the step of an 8-bit color.
            assertEquals(toPlug, toTile, 0.2f)
        }
    }

    @Test
    fun `contrast is the ratio of the lighter to the darker luminance`() {
        assertEquals(21f, linkContrast(Color.Black, Color.White), 0.01f)
        assertEquals(1f, linkContrast(Color.White, Color.White), 0f)
    }
}

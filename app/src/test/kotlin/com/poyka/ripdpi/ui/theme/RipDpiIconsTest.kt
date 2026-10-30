package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiIconsTest {
    private val icons: Map<String, ImageVector> =
        RipDpiIcons.javaClass.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .associate {
                it.isAccessible = true
                it.name.removePrefix("get") to it.invoke(RipDpiIcons) as ImageVector
            }

    @Test
    fun `every icon is a 24dp symbol on a 960 viewport`() {
        assertTrue("expected the full icon set, got ${icons.size}", icons.size >= 48)
        icons.forEach { (name, icon) ->
            assertEquals(name, 24f, icon.defaultWidth.value, 0f)
            assertEquals(name, 24f, icon.defaultHeight.value, 0f)
            assertEquals(name, 960f, icon.viewportWidth, 0f)
            assertEquals(name, 960f, icon.viewportHeight, 0f)
        }
    }

    @Test
    fun `favorite has a distinct filled variant`() {
        assertNotEquals(RipDpiIcons.Favorite.root, RipDpiIcons.FavoriteFilled.root)
    }

    @Test
    fun `only directional icons auto mirror`() {
        val mirrored = icons.filterValues { it.autoMirror }.keys
        assertEquals(listOf("Back", "ChevronRight", "Logs", "MoveTo", "OpenExternal"), mirrored.sorted())
    }
}

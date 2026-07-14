package com.poyka.ripdpi.ui.components.inputs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RipDpiConnectionActuatorDrawPhaseTest {
    @Test
    fun `frame animation and drag state are read after composition`() {
        val source = source().readText()

        assertFalse(source.contains("val railColor by animateColorAsState"))
        assertFalse(source.contains("val carriageColor by animateColorAsState"))
        assertFalse(source.contains("val terminalColor by animateColorAsState"))
        assertFalse(source.contains("val baseFraction by animateFloatAsState"))
        assertFalse(source.contains("pulseAlpha.value\n        } else"))
        assertTrue(source.contains("interactionModifier.dragDeltaPx.value / travelPx"))
        assertTrue(source.contains("baseFraction.value + dragFraction"))
        assertTrue(source.contains("drawRect(railColor.value)"))
        assertTrue(source.contains("drawRect(container.value)"))
        assertTrue(source.contains("drawRect(carriageColor.value)"))
        assertTrue(source.contains("drawRect(style.container.copy(alpha = pulseAlpha.value))"))
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiConnectionActuator.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiConnectionActuator.kt"),
        ).first(File::isFile)
}

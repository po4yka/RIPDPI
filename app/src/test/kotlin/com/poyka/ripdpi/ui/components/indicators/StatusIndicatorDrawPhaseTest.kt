package com.poyka.ripdpi.ui.components.indicators

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StatusIndicatorDrawPhaseTest {
    @Test
    fun `frame animation state is read only by canvas drawing`() {
        val source = source().readText()
        val rowBody = source.substringAfter("Row(").substringBefore("@Preview")

        assertFalse(source.contains("animatedIndicatorColor by animateColorAsState"))
        assertFalse(source.contains("pulseScale by"))
        assertFalse(source.contains("pulseAlpha by"))
        assertFalse(source.contains(".scale(pulseScale)"))
        assertTrue(rowBody.contains("animatedIndicatorColor.value"))
        assertTrue(rowBody.contains("pulseAlpha.value"))
        assertTrue(rowBody.contains("pulseScale.value"))
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/StatusIndicator.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/StatusIndicator.kt"),
        ).first(File::isFile)
}

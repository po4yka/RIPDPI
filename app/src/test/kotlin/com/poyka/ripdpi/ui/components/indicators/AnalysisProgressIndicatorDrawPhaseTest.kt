package com.poyka.ripdpi.ui.components.indicators

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnalysisProgressIndicatorDrawPhaseTest {
    @Test
    fun `frame animation state is read only by layout and drawing`() {
        val source = source().readText()
        val segmentBody = source.substringAfter("private fun PipelineSegment(").substringBefore("@Preview")

        assertFalse(source.contains("val pulseAlpha by"))
        assertFalse(source.contains("val shimmerAlpha by"))
        assertFalse(segmentBody.contains("val animatedColor by"))
        assertFalse(segmentBody.contains("val fillFraction by"))
        assertFalse(segmentBody.contains("if (fillFraction > 0f)"))
        assertTrue(segmentBody.contains("scaleX = fillFraction.value"))
        assertTrue(segmentBody.contains("pipelineAlphas.pulse.value"))
        assertTrue(segmentBody.contains("pipelineAlphas.shimmer.value"))
        assertTrue(segmentBody.contains("drawRect(animatedColor.value)"))
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/AnalysisProgressIndicator.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/AnalysisProgressIndicator.kt"),
        ).first(File::isFile)
}

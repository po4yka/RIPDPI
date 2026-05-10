package com.poyka.ripdpi.core.detection.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusVisualResolverTest {
    @Test
    fun `no two states share same shape in any palette`() {
        DetectionColorVisionMode.entries.forEach { mode ->
            val shapes =
                DetectionVisualState.entries.map { state ->
                    StatusVisualResolver.resolve(state = state, mode = mode).shape
                }

            assertEquals(
                "Palette $mode must keep every status visually distinct by shape",
                DetectionVisualState.entries.size,
                shapes.toSet().size,
            )
        }
    }

    @Test
    fun `standard palette matches status shape contract`() {
        assertEquals(
            StatusShapeVariant.CIRCLE,
            StatusVisualResolver.resolve(DetectionVisualState.CLEAN, DetectionColorVisionMode.OFF).shape,
        )
        assertEquals(
            StatusShapeVariant.TRIANGLE,
            StatusVisualResolver.resolve(DetectionVisualState.REVIEW, DetectionColorVisionMode.OFF).shape,
        )
        assertEquals(
            StatusShapeVariant.DIAMOND,
            StatusVisualResolver.resolve(DetectionVisualState.DETECTED, DetectionColorVisionMode.OFF).shape,
        )
        assertEquals(
            StatusShapeVariant.SQUARE,
            StatusVisualResolver.resolve(DetectionVisualState.ERROR, DetectionColorVisionMode.OFF).shape,
        )
        assertEquals(
            StatusShapeVariant.LINE,
            StatusVisualResolver.resolve(DetectionVisualState.NEUTRAL, DetectionColorVisionMode.OFF).shape,
        )
    }

    @Test
    fun `color vision mode accepts legacy deficiency wire values`() {
        assertEquals(DetectionColorVisionMode.OFF, DetectionColorVisionMode.fromWire(""))
        assertEquals(DetectionColorVisionMode.RED_GREEN, DetectionColorVisionMode.fromWire("protanopia"))
        assertEquals(DetectionColorVisionMode.RED_GREEN, DetectionColorVisionMode.fromWire("deuteranomaly"))
        assertEquals(DetectionColorVisionMode.BLUE_YELLOW, DetectionColorVisionMode.fromWire("tritanopia"))
        assertEquals(DetectionColorVisionMode.ACHROMATOPSIA, DetectionColorVisionMode.fromWire("achromatopsia"))
    }
}

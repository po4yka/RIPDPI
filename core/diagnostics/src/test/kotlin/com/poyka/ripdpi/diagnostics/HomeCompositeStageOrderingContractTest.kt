package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageOrderingContractTest {
    @Test
    fun `progress declaration orders dpi full before path comparison before dpi strategy`() {
        val canonicalTail = setOf("dpi_full", "path_comparison", "dpi_strategy")

        assertEquals(
            listOf("dpi_full", "path_comparison", "dpi_strategy"),
            HomeCompositeStageSpecs.map(HomeCompositeStageSpec::key).filter(canonicalTail::contains),
        )
    }
}

package com.poyka.ripdpi.ui.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LifecycleEventEffectTest {
    @Test
    fun `shared flow events remain queued without a UI collector`() =
        runTest {
            val producer = MutableSharedFlow<String>(extraBufferCapacity = 1)
            val events = producer.bufferForUiLifecycle(backgroundScope)

            assertTrue(producer.tryEmit("pending"))
            runCurrent()

            assertEquals("pending", events.first())
        }

    @Test
    fun `one shot collectors use the resumed lifecycle helper`() {
        val helper = source("ui/components/LifecycleEventEffect.kt").readText()
        val productionSources = source(".").walkTopDown().filter { it.extension == "kt" }.toList()

        assertTrue(helper.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertFalse(productionSources.any { it.readText().contains("effects.collect") })
        assertFalse(productionSources.any { it.readText().contains("savedEvents.collect") })
        assertFalse(productionSources.any { it.readText().contains("importedEvents.collect") })
        assertFalse(productionSources.any { it.readText().contains("appliedEvents.collect") })
        assertFalse(productionSources.any { it.readText().contains("uiEvents.collect") })
    }

    private fun source(relativePath: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/$relativePath"),
            File("app/src/main/kotlin/com/poyka/ripdpi/$relativePath"),
        ).first(File::exists)
}

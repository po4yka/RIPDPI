package com.poyka.ripdpi.ui.screenshot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FullExperienceScreenshotBaselineTest {
    @Test
    fun `full experience previews do not create Simple baselines`() {
        val screenshotDirectory = File(System.getProperty("user.dir"), "src/test/screenshots")
        val unreachableSimpleBaselines =
            screenshotDirectory
                .walkTopDown()
                .filter { it.isFile && "_simple_" in it.name && it.extension == "png" }
                .map(File::getName)
                .sorted()
                .toList()

        assertTrue(
            "Full-only preview baselines cannot represent the Simple experience: $unreachableSimpleBaselines",
            unreachableSimpleBaselines.isEmpty(),
        )
    }
}

package com.poyka.ripdpi.ui.screenshot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FullExperienceScreenshotBaselineTest {
    @Test
    fun `experience-isolated setup health previews keep both variants`() {
        val screenshotDirectory = File(System.getProperty("user.dir"), "src/test/screenshots")
        val expectedBaselines =
            ExperienceIsolatedSetupHealthPreviews.flatMap { preview ->
                listOf("full", "simple").flatMap { experience ->
                    listOf("light", "dark").map { theme ->
                        "$ConfigSetupHealthScreenshotTestFqn.${preview}_${experience}_$theme.png"
                    }
                }
            }
        val missingBaselines = expectedBaselines.filterNot { File(screenshotDirectory, it).isFile }

        assertTrue(
            "Experience-isolated previews require Full and Simple baselines: $missingBaselines",
            missingBaselines.isEmpty(),
        )
    }
}

private const val ConfigSetupHealthScreenshotTestFqn =
    "com.poyka.ripdpi.ui.screenshot.ConfigSetupHealthScreenshotTest"
private val ExperienceIsolatedSetupHealthPreviews =
    listOf(
        "setupHealthPermissionRecovery",
        "setupHealthBatteryRecommendation",
        "homeSetupAtPixel7LargeFont",
        "homeTransitionAtPixel7MaximumFont",
    )

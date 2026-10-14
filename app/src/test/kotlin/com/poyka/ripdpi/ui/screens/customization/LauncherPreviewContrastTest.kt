package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w411dp-h1600dp")
class LauncherPreviewContrastTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun everyLauncherArtworkHasVisibleLightAndDarkPixelsInBothThemes() {
        val theme = mutableStateOf("light")
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme(themePreference = theme.value) {
                    Box(Modifier.requiredWidth(360.dp)) {
                        IconPickerGrid(
                            options = LauncherIconManager.availableIcons,
                            selectedKey = "",
                            onOptionSelected = {},
                        )
                    }
                }
            }
        }
        listOf("light", "dark").forEach { preference ->
            composeRule.runOnIdle { theme.value = preference }
            LauncherIconManager.availableIcons.forEach { option ->
                assertArtworkContrast(option.key, preference)
            }
        }
    }

    private fun assertArtworkContrast(
        key: String,
        theme: String,
    ) {
        val pixels =
            composeRule
                .onNodeWithTag(RipDpiTestTags.customizationIconPreview(key), useUnmergedTree = true)
                .assertWidthIsEqualTo(56.dp)
                .assertHeightIsEqualTo(56.dp)
                .captureToImage()
                .toPixelMap()
        var light = 0
        var dark = 0
        var total = 0
        val centerX = (pixels.width - 1) / 2f
        val centerY = (pixels.height - 1) / 2f
        val radius = minOf(pixels.width, pixels.height) / 2f - 2f
        // The inscribed circle excludes the border and clipped corners, but includes the artwork background.
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy > radius * radius) continue
                val color = pixels[x, y]
                val brightness = (color.red + color.green + color.blue) / 3f
                if (brightness > 0.8f) light++
                if (brightness < 0.2f) dark++
                total++
            }
        }
        assertTrue("$key on $theme has no light contrast", light > total / 100)
        assertTrue("$key on $theme has no dark contrast", dark > total / 100)
        val option = LauncherIconManager.resolveOption(key)
        val background = Color(RuntimeEnvironment.getApplication().getColor(option.previewBackgroundRes))
        // Keep space between the silhouette and the mask; exclude the one-dp border and rounded corners.
        listOf(3, pixels.height - 4).forEach { y ->
            for (x in pixels.width * 2 / 5 until pixels.width * 3 / 5) {
                val pixel = pixels[x, y]
                val difference =
                    maxOf(
                        kotlin.math.abs(pixel.red - background.red),
                        kotlin.math.abs(pixel.green - background.green),
                        kotlin.math.abs(pixel.blue - background.blue),
                    )
                assertTrue("$key on $theme touches its top or bottom mask at ($x,$y)", difference < 0.05f)
            }
        }
    }
}

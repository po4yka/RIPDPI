package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.ui.components.contrastRatio
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.ripDpiLightColorScheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiOutlineBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `outline button boundaries hold 3 to 1 when enabled and fade when disabled`() {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                Column {
                    RipDpiButton("On", {}, Modifier.ripDpiTestTag("button-on"), variant = RipDpiButtonVariant.Outline)
                    RipDpiButton(
                        "Off",
                        {},
                        Modifier.ripDpiTestTag("button-off"),
                        variant = RipDpiButtonVariant.Outline,
                        enabled = false,
                    )
                    RipDpiIconButton(
                        RipDpiIcons.Copy,
                        "On",
                        {},
                        Modifier.ripDpiTestTag("icon-on"),
                        style = RipDpiIconButtonStyle.Outline,
                    )
                    RipDpiIconButton(
                        RipDpiIcons.Copy,
                        "Off",
                        {},
                        Modifier.ripDpiTestTag("icon-off"),
                        style = RipDpiIconButtonStyle.Outline,
                        enabled = false,
                    )
                }
            }
        }
        val page = ripDpiLightColorScheme().surface
        listOf("button", "icon").forEach { kind ->
            // The top edge sits clear of the corner radius at mid-width; the border is the first pixel row.
            fun edge(state: String): Float {
                val image = composeRule.onNodeWithTag("$kind-$state").captureToImage().toPixelMap()
                return contrastRatio(image[image.width / 2, 0], page)
            }
            assertTrue("$kind enabled boundary was ${edge("on")}:1", edge("on") >= 3f)
            assertTrue("$kind disabled boundary should fade", edge("off") < edge("on"))
        }
    }
}

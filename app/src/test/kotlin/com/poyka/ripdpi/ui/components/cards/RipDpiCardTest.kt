package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.ripDpiLightColorScheme
import org.junit.Assert.assertEquals
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
class RipDpiCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun hasButtonRole() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun `clickable card and banner expose the button role`() {
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    RipDpiCard(Modifier.ripDpiTestTag("card"), onClick = { clicks++ }) { Spacer(Modifier.size(8.dp)) }
                    WarningBanner("Title", "Message", Modifier.ripDpiTestTag("banner"), onClick = {}, onDismiss = {})
                }
            }
        }
        composeRule.onNodeWithTag("card").assert(hasButtonRole()).performClick()
        composeRule.onNodeWithTag("banner").assert(hasButtonRole())
        composeRule.onNodeWithTag(RipDpiTestTags.WarningBannerDismiss).assert(hasButtonRole())
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `static card is not a button`() {
        composeRule.setContent {
            RipDpiTheme { RipDpiCard(Modifier.ripDpiTestTag("card")) { Spacer(Modifier.size(8.dp)) } }
        }
        composeRule
            .onNodeWithTag("card")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }

    @Test
    fun `elevated card keeps the exact surface color without a tonal tint`() {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                RipDpiCard(Modifier.ripDpiTestTag("card"), variant = RipDpiCardVariant.Elevated) {
                    Spacer(Modifier.size(80.dp))
                }
            }
        }
        val image = composeRule.onNodeWithTag("card").captureToImage().toPixelMap()
        val center = image[image.width / 2, image.height / 2]
        val surface = ripDpiLightColorScheme().surface
        assertTrue(
            "Expected $surface at the card center, was $center",
            kotlin.math.abs(center.red - surface.red) < 0.005f &&
                kotlin.math.abs(center.green - surface.green) < 0.005f &&
                kotlin.math.abs(center.blue - surface.blue) < 0.005f,
        )
    }

    @Test
    fun `a tonal card nested in a tonal card steps one container up`() {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                RipDpiCard(Modifier.ripDpiTestTag("outer"), variant = RipDpiCardVariant.Tonal) {
                    RipDpiCard(Modifier.ripDpiTestTag("inner"), variant = RipDpiCardVariant.Tonal) {
                        Spacer(Modifier.size(80.dp))
                    }
                }
            }
        }
        val scheme = ripDpiLightColorScheme()

        fun center(tag: String): androidx.compose.ui.graphics.Color {
            val image = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            return image[image.width / 2, image.height / 2]
        }
        val inner = center("inner")
        val outerEdge =
            composeRule
                .onNodeWithTag("outer")
                .captureToImage()
                .toPixelMap()
                .let { it[2, it.height / 2] }
        assertEquals(scheme.surfaceContainerHigh.toArgb(), inner.toArgb())
        assertEquals(scheme.surfaceContainer.toArgb(), outerEdge.toArgb())
    }
}

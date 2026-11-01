package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.ContrastAssertions.Enhanced
import com.poyka.ripdpi.ui.theme.ContrastAssertions.Text
import com.poyka.ripdpi.ui.theme.ContrastAssertions.assertAtLeast
import com.poyka.ripdpi.ui.theme.ContrastAssertions.ratio
import com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
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
class RipDpiMaterialButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val light = LightRipDpiExtendedColors

    @Test
    fun `primary keeps the ink fill and disabled uses the muted palette`() {
        lateinit var primary: ButtonColors
        lateinit var destructive: ButtonColors
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                primary = ripDpiButtonColors(RipDpiButtonVariant.Primary)
                destructive = ripDpiButtonColors(RipDpiButtonVariant.Destructive)
            }
        }
        composeRule.runOnIdle {
            assertEquals(light.foreground, primary.containerColor)
            assertEquals(light.background, primary.contentColor)
            assertEquals(light.border, primary.disabledContainerColor)
            assertEquals(light.mutedForeground, primary.disabledContentColor)
            assertEquals(light.destructive, destructive.containerColor)
            assertEquals(light.destructiveForeground, destructive.contentColor)
        }
    }

    @Test
    fun `dark primary stands out from the page and the disabled fill`() {
        lateinit var primary: ButtonColors
        lateinit var colors: RipDpiExtendedColors
        composeRule.setContent {
            RipDpiTheme(themePreference = "dark") {
                primary = ripDpiButtonColors(RipDpiButtonVariant.Primary)
                colors = RipDpiThemeTokens.colors
            }
        }
        composeRule.runOnIdle {
            assertAtLeast("primary on the page", Enhanced, primary.containerColor, colors.background)
            assertAtLeast("primary on the disabled fill", Text, primary.containerColor, primary.disabledContainerColor)
            assertAtLeast("label on primary", Text, primary.contentColor, primary.containerColor)
            // The chip and tonal fills are onSurface; the primary action must read stronger than them.
            assertTrue(
                ratio(primary.containerColor, colors.background) > ratio(colors.foreground, colors.background),
            )
        }
    }

    @Test
    fun `destructive and warning icon buttons keep a transparent container and a semantic tint`() {
        lateinit var destructive: IconButtonColors
        lateinit var warning: IconButtonColors
        lateinit var disabled: IconButtonColors
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                destructive =
                    ripDpiIconButtonColors(RipDpiIconButtonStyle.Destructive, enabled = true, selected = false)
                warning = ripDpiIconButtonColors(RipDpiIconButtonStyle.Warning, enabled = true, selected = false)
                disabled = ripDpiIconButtonColors(RipDpiIconButtonStyle.Destructive, enabled = false, selected = false)
            }
        }
        composeRule.runOnIdle {
            assertEquals(light.destructive, destructive.contentColor)
            assertEquals(light.warning, warning.contentColor)
            assertEquals(Color.Transparent, destructive.containerColor)
            assertEquals(Color.Transparent, warning.containerColor)
            // A disabled destructive icon stops shouting: the foreground at the standard 38% alpha.
            assertEquals(light.foreground.copy(alpha = 0.38f), disabled.disabledContentColor)
        }
    }

    @Test
    fun `button keeps the 48dp target and loading blocks activation`() {
        var loading by mutableStateOf(false)
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                RipDpiButton(
                    text = "Go",
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("button"),
                    loading = loading,
                )
            }
        }
        val button = composeRule.onNodeWithTag("button")
        button.assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, clicks)
            loading = true
        }
        button.assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}

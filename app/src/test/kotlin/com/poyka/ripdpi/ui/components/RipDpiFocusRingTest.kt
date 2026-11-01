package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.components.buttons.RipDpiTopBarAction
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiContrastLevel
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.ripDpiDarkColorScheme
import com.poyka.ripdpi.ui.theme.ripDpiExtendedColors
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
class RipDpiFocusRingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ring color holds 3 to 1 against every container in every palette`() {
        listOf(false, true).forEach { isDark ->
            RipDpiContrastLevel.entries.forEach { level ->
                val scheme = if (isDark) ripDpiDarkColorScheme(level) else ripDpiLightColorScheme(level)
                val c = ripDpiExtendedColors(scheme, isDark, level)
                listOf(c.foreground, c.accent, c.muted, c.destructive, c.background, c.warning).forEach { container ->
                    val ring = ripDpiFocusRingColor(container, c.foreground, c.background)
                    val ratio = contrastRatio(ring, container)
                    assertTrue("dark=$isDark $level container=$container ring=$ring was $ratio", ratio >= 3f)
                }
            }
        }
    }

    @Test
    fun `focused buttons draw a ring that differs from the unfocused edge by 3 to 1`() {
        composeRule.setContent {
            EnableKeyboardInput()
            RipDpiTheme(themePreference = "light") {
                Column {
                    RipDpiButton("Primary", {}, Modifier.ripDpiTestTag("primary"))
                    RipDpiButton("Ghost", {}, Modifier.ripDpiTestTag("ghost"), variant = RipDpiButtonVariant.Ghost)
                    RipDpiTextAction("Text", {}, Modifier.ripDpiTestTag("text"))
                    RipDpiTopBarAction("Save", {}, Modifier.ripDpiTestTag("topbar"))
                    RipDpiIconButton(
                        RipDpiIcons.Copy,
                        "Copy",
                        {},
                        Modifier.ripDpiTestTag("icon"),
                        style = RipDpiIconButtonStyle.Filled,
                    )
                }
            }
        }
        listOf("primary", "ghost", "icon", "text", "topbar").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            val before = node.captureToImage().toPixelMap()
            node.requestFocus()
            composeRule.waitForIdle()
            val after = node.captureToImage().toPixelMap()
            // Mid-width of the top edge, inside the 2dp ring and clear of the corner radius.
            val x = before.width / 2
            val ratio = contrastRatio(after[x, 1], before[x, 1])
            assertTrue("$tag focus ring changed the edge by only $ratio:1", ratio >= 3f)
        }
    }
}

package com.poyka.ripdpi.ui.components.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scaffold width tiers are a rendered contract, not a data one.
 *
 * [com.poyka.ripdpi.ui.theme.RipDpiLayoutTest] already covers the pure
 * `ripDpiLayoutForWidth` mapping, and it stayed green for months while every
 * scaffold silently ignored the cap it returns: `fillMaxWidth()` ahead of
 * `widthIn(max = ...)` pins `min == max` to the parent width, so `widthIn` has
 * no room left to constrain. Only a measured body catches that.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1040dp-h920dp")
class RipDpiScaffoldWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val BodyTag = "scaffold-body"

        /** Absorbs sub-pixel rounding between the dp cap and the measured bounds. */
        val Tolerance = 1.dp
    }

    @Test
    fun `dashboard body honours the content width cap on an expanded window`() {
        var contentMaxWidth: Dp = Dp.Unspecified
        var horizontalPadding: Dp = Dp.Unspecified
        var widthClass: RipDpiWidthClass? = null

        composeRule.setContent {
            RipDpiTheme {
                val layout = RipDpiThemeTokens.layout
                contentMaxWidth = layout.contentMaxWidth
                horizontalPadding = layout.horizontalPadding
                widthClass = layout.widthClass

                RipDpiDashboardScaffold(topBar = {}) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .testTag(BodyTag),
                    )
                }
            }
        }

        // Guard: on a narrow harness window the cap is not binding and the
        // assertion below would pass no matter how the modifiers are ordered.
        assertEquals(RipDpiWidthClass.Expanded, widthClass)

        val bodyWidth = composeRule.onNodeWithTag(BodyTag).getUnclippedBoundsInRoot().width
        val ceiling = contentMaxWidth - horizontalPadding * 2
        assertTrue(
            "dashboard body measured $bodyWidth, above the $ceiling ceiling " +
                "(contentMaxWidth=$contentMaxWidth, horizontalPadding=$horizontalPadding)",
            bodyWidth <= ceiling + Tolerance,
        )
    }
}

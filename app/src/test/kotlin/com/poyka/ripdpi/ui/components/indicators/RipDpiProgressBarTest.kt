package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiProgressBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `unknown progress remains indeterminate when animations are disabled`() {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiProgressBar(progress = null, modifier = Modifier.ripDpiTestTag("progress"))
                }
            }
        }

        composeRule.onNodeWithTag("progress").assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    @Test
    fun `unknown progress remains indeterminate with reduced motion`() {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides DefaultRipDpiMotion.copy(reducedMotion = true)) {
                    RipDpiProgressBar(progress = null, modifier = Modifier.ripDpiTestTag("progress"))
                }
            }
        }
        composeRule.onNodeWithTag("progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    @Test
    fun `preview keeps unknown progress indeterminate`() {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    RipDpiProgressBar(progress = null, modifier = Modifier.ripDpiTestTag("progress"))
                }
            }
        }
        composeRule.onNodeWithTag("progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    @Test
    fun `known progress keeps its measured value without animation`() {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiProgressBar(progress = 0.4f, modifier = Modifier.ripDpiTestTag("progress"))
                }
            }
        }
        composeRule.onNodeWithTag("progress").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.4f, 0f..1f),
            ),
        )
    }
}

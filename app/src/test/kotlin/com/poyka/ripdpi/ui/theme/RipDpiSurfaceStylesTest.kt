@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.cards.SettingsRowDecoration
import com.poyka.ripdpi.ui.components.routes.RipDpiRouteAvailabilityState
import com.poyka.ripdpi.ui.components.routes.RipDpiRouteOpportunityPanel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiSurfaceStylesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val visualOverride =
        CommonStyle {
            rdsBackground(Color.Blue)
            rdsBorder(Color.Red, 0.dp)
            rdsMinHeight(1.dp)
        }

    @Test
    fun `card override does not enable disabled actions`() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiCard(
                    modifier = Modifier.testTag(Target),
                    enabled = enabled,
                    onClick = { clicks++ },
                    visualStyle = visualOverride,
                ) {
                    Box(Modifier.size(48.dp))
                }
            }
        }
        val card = composeRule.onNodeWithTag(Target)
        card.performClick()
        composeRule.runOnIdle {
            assertEquals(1, clicks)
            enabled = false
        }
        card.assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `settings override keeps toggle state and minimum touch target`() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        composeRule.setContent {
            RipDpiTheme {
                SettingsRow(
                    title = "Toggle setting",
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = { checked = it },
                    decoration = SettingsRowDecoration(testTag = Target),
                    visualStyle = visualOverride,
                )
            }
        }
        val row = composeRule.onNodeWithTag(Target)
        row
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle { enabled = false }
        row.assertIsNotEnabled().performTouchInput { click() }
        row.assertIsOn()
    }

    @Test
    fun `preset override preserves radio selection and disabled state`() {
        var selected by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                PresetCard(
                    title = "Preset",
                    description = "A local route preset",
                    modifier = Modifier.testTag(Target),
                    selected = selected,
                    enabled = enabled,
                    visualStyle = visualOverride,
                    onClick = {
                        clicks++
                        selected = true
                    },
                )
            }
        }
        val preset = composeRule.onNodeWithTag(Target)
        preset.assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        composeRule.runOnIdle { enabled = false }
        preset.assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `trailing route action remains a callback and does not become a style`() {
        var actions = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteOpportunityPanel(
                    title = "Route",
                    message = "A local route is available",
                    state = RipDpiRouteAvailabilityState.Available,
                    actionLabel = "Apply",
                ) { actions++ }
            }
        }
        composeRule.runOnIdle { assertEquals(0, actions) }
        composeRule.onNodeWithTag(RipDpiTestTags.RouteOpportunityPanel).performClick()
        composeRule.runOnIdle { assertEquals(1, actions) }
    }

    private companion object {
        const val Target = "style-surface-target"
    }
}

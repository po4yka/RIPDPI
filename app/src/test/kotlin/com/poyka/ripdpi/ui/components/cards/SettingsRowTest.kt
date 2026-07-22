package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class SettingsRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun valueRowsExposeTitleSubtitleAndValueToAccessibility() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsRow(
                    title = "DNS provider",
                    subtitle = "Encrypted resolver",
                    value = "Cloudflare DoH",
                    testTag = "settings-row",
                )
            }
        }

        composeRule
            .onNodeWithTag("settings-row")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("DNS provider, Encrypted resolver, Cloudflare DoH"),
                ),
            )
    }

    @Test
    fun clickableValueRowsKeepTrailingValueInMergedAccessibilityNode() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsRow(
                    title = "Relay server",
                    subtitle = "Remote endpoint",
                    value = "relay.example.net:443",
                    onClick = {},
                    testTag = "settings-clickable-value-row",
                )
            }
        }

        composeRule
            .onNodeWithTag("settings-clickable-value-row")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Relay server, Remote endpoint, relay.example.net:443"),
                ),
            )
    }

    @Test
    fun switchRowsExposeTitleSubtitleAndStateToAccessibility() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsRow(
                    title = "Block UDP DNS",
                    subtitle = "Prevents resolver leaks",
                    checked = true,
                    onCheckedChange = {},
                    testTag = "settings-switch-row",
                )
            }
        }

        composeRule
            .onNodeWithTag("settings-switch-row")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Block UDP DNS, Prevents resolver leaks"),
                ),
            ).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "On",
                ),
            )
    }

    @Test
    fun largeFontStacksTrailingValueBelowReadableTitleAndSubtitle() {
        var fontScale by mutableStateOf(1.5f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(371.dp)) {
                        SettingsRow(
                            title = "DNS settings",
                            subtitle = "Resolver mode, protocol, IPv6, and local tunnel DNS behavior.",
                            value = "Encrypted DNS · Cloudflare (DoH)",
                            onClick = {},
                            testTag = "large-settings-row",
                        )
                    }
                }
            }
        }

        listOf(1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()

            val title = assertCompleteText("DNS settings")
            val subtitle = assertCompleteText("Resolver mode, protocol, IPv6, and local tunnel DNS behavior.")
            val value = assertCompleteText("Encrypted DNS · Cloudflare (DoH)")

            assertTrue(title.boundsInRoot.right <= 371f)
            assertTrue(subtitle.boundsInRoot.right <= 371f)
            assertTrue(value.boundsInRoot.top >= subtitle.boundsInRoot.bottom)
            assertTrue(value.boundsInRoot.right <= 371f)
            composeRule.onNodeWithTag("large-settings-row").assertHasClickAction()
        }
    }

    private fun assertCompleteText(text: String) =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .also { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                val layout = layouts.single()
                assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
                assertTrue((0 until layout.lineCount).all { line -> layout.getLineLeft(line) >= -1f })
                assertTrue(
                    (0 until layout.lineCount).all { line ->
                        layout.getLineRight(line) <= layout.size.width + 1f
                    },
                )
            }.fetchSemanticsNode()
}

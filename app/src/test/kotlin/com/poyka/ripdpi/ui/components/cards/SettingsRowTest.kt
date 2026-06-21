package com.poyka.ripdpi.ui.components.cards

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}

package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class HomeModeCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all active and inactive preview states`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    HomeMode.entries.forEach { mode ->
                        HomeModeCard(uiState = card(mode = mode, active = true), onPrimaryAction = {}, onConfigure = {})
                        HomeModeCard(uiState = card(mode = mode), onPrimaryAction = {}, onConfigure = {})
                    }
                }
            }
        }

        HomeMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(2)
        }
    }

    @Test
    fun `primary action disabled state is exposed through semantics`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeModeCard(
                    uiState = card(primaryActionEnabled = false),
                    onPrimaryAction = {},
                    onConfigure = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.LocalDpiBypass.name))
            .assertIsNotEnabled()
    }

    @Test
    fun `disabled vpn action renders relay hint and routes hint click`() {
        var hintCalls = 0
        var configureCalls = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeModeCard(
                    uiState =
                        card(
                            mode = HomeMode.RemoteVpn,
                            primaryLabel = "Relay disabled",
                            primaryActionEnabled = false,
                            primaryActionDisabledHint = "Enable a relay in Configure to turn this on",
                        ),
                    onPrimaryAction = {},
                    onConfigure = { configureCalls++ },
                    onDisabledHintClick = { hintCalls++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.RemoteVpn.name))
            .assertIsNotEnabled()
        composeRule
            .onNodeWithText("Enable a relay in Configure to turn this on")
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeModeDisabledHint)
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, hintCalls)
            assertEquals(0, configureCalls)
        }
    }

    @Test
    fun `tapping card body calls card action without calling configure action`() {
        var cardCalls = 0
        var configureCalls = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeModeCard(
                    uiState = card(),
                    onPrimaryAction = {},
                    onConfigure = { configureCalls++ },
                    onCardClick = { cardCalls++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCardBody(HomeMode.LocalDpiBypass.name))
            .performTouchInput {
                click(Offset(center.x, center.y / 2f))
            }

        composeRule.runOnIdle {
            assertEquals(1, cardCalls)
            assertEquals(0, configureCalls)
        }
    }

    private fun card(
        mode: HomeMode = HomeMode.LocalDpiBypass,
        active: Boolean = false,
        loading: Boolean = false,
        primaryLabel: String? = null,
        primaryActionEnabled: Boolean = true,
        primaryActionDisabledHint: String = "",
    ): HomeModeCardUiState =
        HomeModeCardUiState(
            mode = mode,
            title =
                when (mode) {
                    HomeMode.LocalDpiBypass -> "Local bypass"
                    HomeMode.RemoteVpn -> "VPN"
                    HomeMode.Diagnostic -> "Diagnostic Scan"
                },
            primaryLabel =
                primaryLabel
                    ?: when (mode) {
                        HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                        HomeMode.RemoteVpn -> "relay.example"
                        HomeMode.Diagnostic -> "No analysis yet"
                    },
            statusLine =
                when {
                    loading -> "Busy"
                    active -> "Connected 00:01:00"
                    else -> "Inactive"
                },
            primaryActionLabel =
                when {
                    mode == HomeMode.Diagnostic -> "Run Scan"
                    active -> "Disable"
                    else -> "Enable"
                },
            configureLabel = "Configure",
            primaryActionEnabled = primaryActionEnabled,
            primaryActionDisabledHint = primaryActionDisabledHint,
            isActive = active,
            isLoading = loading,
        )
}

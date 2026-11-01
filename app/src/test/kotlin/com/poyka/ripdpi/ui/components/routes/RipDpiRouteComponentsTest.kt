package com.poyka.ripdpi.ui.components.routes

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.ContrastAssertions
import com.poyka.ripdpi.ui.theme.RipDpiContrastLevel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
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
class RipDpiRouteComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `route profile exposes selectable semantics and click callback`() {
        var clicks = 0

        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteProfileCard(
                    state = routeProfile(selected = true),
                    onClick = { clicks++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.routeProfile("local-vpn"))
            .assertIsDisplayed()
            .assertIsSelected()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clicks)
        }
    }

    @Test
    fun `restricted route profile is disabled`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteProfileCard(
                    state = routeProfile(state = RipDpiRouteAvailabilityState.Restricted),
                    onClick = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.routeProfile("local-vpn"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `capability pill exposes stable tag and label`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteCapabilityPill(
                    capability =
                        RipDpiRouteCapabilityUiState(
                            kind = RipDpiRouteCapabilityKind.VpnPrivacy,
                            label = "VPN",
                        ),
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.routeCapability("vpn-privacy"))
            .assertIsDisplayed()
    }

    @Test
    fun `route stack exposes path accessibility description`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteStackDiagram(
                    state =
                        RipDpiRouteStackUiState(
                            nodes =
                                persistentListOf(
                                    RipDpiRouteStackNodeUiState(
                                        id = "device",
                                        label = "Device",
                                        transportKind = RipDpiRouteTransportKind.LocalVpn,
                                    ),
                                    RipDpiRouteStackNodeUiState(
                                        id = "dns",
                                        label = "DNS",
                                        transportKind = RipDpiRouteTransportKind.Warp,
                                    ),
                                    RipDpiRouteStackNodeUiState(
                                        id = "relay",
                                        label = "Relay",
                                        transportKind = RipDpiRouteTransportKind.Relay,
                                    ),
                                ),
                            activeNodeId = "relay",
                            warningNodeId = "dns",
                        ),
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.RouteStack)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
                    listOf("Secure route stack: Device available to DNS warning to Relay active"),
                ),
            )
    }

    @Test
    fun `opportunity panel can expose an action`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiRouteOpportunityPanel(
                    title = "Provider route ready",
                    message = "Add credentials to use this route.",
                    state = RipDpiRouteAvailabilityState.NeedsSetup,
                    actionLabel = "Setup",
                    onAction = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.RouteOpportunityPanel)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
    }

    private fun routeProfile(
        selected: Boolean = false,
        state: RipDpiRouteAvailabilityState = RipDpiRouteAvailabilityState.Available,
    ): RipDpiRouteProfileUiState =
        RipDpiRouteProfileUiState(
            id = "local-vpn",
            title = "Local VPN route",
            subtitle = "Routes traffic through the Android VPN tunnel.",
            transportLabel = "Local VPN",
            providerLabel = "RIPDPI",
            capabilities =
                persistentListOf(
                    RipDpiRouteCapabilityUiState(RipDpiRouteCapabilityKind.VpnPrivacy, "VPN"),
                    RipDpiRouteCapabilityUiState(RipDpiRouteCapabilityKind.DnsProtection, "DNS"),
                ),
            state = state,
            isSelected = selected,
            isActive = selected,
        )

    @Test
    fun `route stack node labels are drawn readable on the page in every theme and contrast level`() {
        val schemes = listOf("light", "dark").flatMap { theme -> RipDpiContrastLevel.entries.map { theme to it } }
        val pages = mutableMapOf<Int, Color>()
        composeRule.setContent {
            Column {
                schemes.forEachIndexed { index, (theme, level) ->
                    RipDpiTheme(themePreference = theme, contrastLevel = level) {
                        pages[index] = RipDpiThemeTokens.colors.background
                        RipDpiRouteStackDiagram(
                            state =
                                RipDpiRouteStackUiState(
                                    nodes =
                                        persistentListOf(
                                            RipDpiRouteStackNodeUiState(
                                                "device",
                                                "Device",
                                                RipDpiRouteTransportKind.LocalVpn,
                                            ),
                                            RipDpiRouteStackNodeUiState(
                                                "relay",
                                                "Relay",
                                                RipDpiRouteTransportKind.Relay,
                                            ),
                                        ),
                                    activeNodeId = "relay",
                                ),
                        )
                    }
                }
            }
        }
        listOf("Device", "Relay").forEach { label ->
            schemes.forEachIndexed { index, (theme, level) ->
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule
                    .onAllNodesWithText(label)[index]
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                ContrastAssertions.assertAtLeast(
                    "$theme $level route node label $label",
                    ContrastAssertions.Text,
                    layouts
                        .single()
                        .layoutInput.style.color,
                    requireNotNull(pages[index]),
                )
            }
        }
    }
}

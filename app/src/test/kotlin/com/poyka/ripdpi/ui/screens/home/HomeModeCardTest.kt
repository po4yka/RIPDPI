package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `localized summary labels remain complete at Pixel 7 large font`() {
        val labels = listOf("Estrategia de evasión", "Configuración de DNS cifrado")
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        HomeModeCard(
                            uiState =
                                card(
                                    summaryFacets =
                                        persistentListOf(
                                            HomeModeSummaryFacet(labels[0], "tcp: split(host+1)"),
                                            HomeModeSummaryFacet(labels[1], "DNS cifrado · AdGuard DNS (DoH)"),
                                        ),
                                ),
                            onPrimaryAction = {},
                            onConfigure = {},
                        )
                    }
                }
            }
        }

        labels.forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            val layout = layouts.single()
            assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        }
    }

    @Test
    fun `localized mode title remains complete at Pixel 7 large font`() {
        val title = "Диагностическое сканирование"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        HomeModeCard(
                            uiState = card(mode = HomeMode.Diagnostic).copy(title = title),
                            onPrimaryAction = {},
                            onConfigure = {},
                        )
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val layout = layouts.single()
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
    }

    @Test
    fun `mode actions stack vertically at compact large font`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        HomeModeCard(
                            uiState =
                                card().copy(
                                    primaryActionLabel = "Enable secure route",
                                    configureLabel = "Open configuration",
                                ),
                            onPrimaryAction = {},
                            onConfigure = {},
                        )
                    }
                }
            }
        }

        val primary =
            composeRule
                .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.LocalDpiBypass.name))
                .fetchSemanticsNode()
        val configure =
            composeRule
                .onNodeWithTag(RipDpiTestTags.homeModeConfigureAction(HomeMode.LocalDpiBypass.name))
                .fetchSemanticsNode()
        assertTrue(primary.boundsInRoot.bottom <= configure.boundsInRoot.top)
    }

    private fun card(
        mode: HomeMode = HomeMode.LocalDpiBypass,
        active: Boolean = false,
        loading: Boolean = false,
        primaryLabel: String? = null,
        primaryActionEnabled: Boolean = true,
        primaryActionDisabledHint: String = "",
        summaryFacets: ImmutableList<HomeModeSummaryFacet> = persistentListOf(),
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
            summaryFacets = summaryFacets,
            isActive = active,
            isLoading = loading,
        )
}

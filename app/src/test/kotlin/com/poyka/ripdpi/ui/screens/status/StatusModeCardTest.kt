package com.poyka.ripdpi.ui.screens.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class StatusModeCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all active and inactive preview states`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    StatusMode.entries.forEach { mode ->
                        StatusModeCard(
                            uiState = card(mode = mode, active = true),
                            onPrimaryAction = {},
                            onConfigure = {},
                        )
                        StatusModeCard(uiState = card(mode = mode), onPrimaryAction = {}, onConfigure = {})
                    }
                }
            }
        }

        StatusMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(2)
        }
    }

    @Test
    fun `primary action disabled state is exposed through semantics`() {
        composeRule.setContent {
            RipDpiTheme {
                StatusModeCard(
                    uiState = card(primaryActionEnabled = false),
                    onPrimaryAction = {},
                    onConfigure = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.LocalDpiBypass.name))
            .assertIsNotEnabled()
    }

    @Test
    fun `running diagnostic action stays clickable to stop the run`() {
        var stopped = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusModeCard(
                    uiState =
                        StatusModeCardUiState(
                            mode = StatusMode.Diagnostic,
                            title = "Diagnostics",
                            primaryActionLabel = "Cancel",
                            isLoading = true,
                            primaryActionEnabled = true,
                        ),
                    onPrimaryAction = { stopped++ },
                    onConfigure = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.Diagnostic.name))
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, stopped) }
    }

    @Test
    fun `tapping card body calls card action without calling configure action`() {
        var cardCalls = 0
        var configureCalls = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusModeCard(
                    uiState = card(),
                    onPrimaryAction = {},
                    onConfigure = { configureCalls++ },
                    onCardClick = { cardCalls++ },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCardBody(StatusMode.LocalDpiBypass.name))
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
                        StatusModeCard(
                            uiState =
                                card(
                                    summaryFacets =
                                        persistentListOf(
                                            StatusModeSummaryFacet(labels[0], "tcp: split(host+1)"),
                                            StatusModeSummaryFacet(labels[1], "DNS cifrado · AdGuard DNS (DoH)"),
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
                        StatusModeCard(
                            uiState = card(mode = StatusMode.Diagnostic).copy(title = title),
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
                        StatusModeCard(
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
                .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.LocalDpiBypass.name))
                .fetchSemanticsNode()
        val configure =
            composeRule
                .onNodeWithTag(RipDpiTestTags.homeModeConfigureAction(StatusMode.LocalDpiBypass.name))
                .fetchSemanticsNode()
        assertTrue(primary.boundsInRoot.bottom <= configure.boundsInRoot.top)
    }

    @Test
    fun `long actions wrap at 320dp with large font`() = assertWrappedAction(LayoutDirection.Ltr)

    @Test
    fun `long RTL actions wrap at 320dp with large font`() = assertWrappedAction(LayoutDirection.Rtl)

    private fun assertWrappedAction(direction: LayoutDirection) {
        val label =
            if (direction ==
                LayoutDirection.Rtl
            ) {
                "بدء الفحص التشخيصي للشبكة"
            } else {
                "Запустить диагностическое сканирование"
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        StatusModeCard(
                            card(mode = StatusMode.Diagnostic).copy(primaryActionLabel = label),
                            {},
                            onPrimaryAction = {},
                        )
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
    }

    private fun card(
        mode: StatusMode = StatusMode.LocalDpiBypass,
        active: Boolean = false,
        loading: Boolean = false,
        primaryLabel: String? = null,
        primaryActionEnabled: Boolean = true,
        summaryFacets: ImmutableList<StatusModeSummaryFacet> = persistentListOf(),
    ): StatusModeCardUiState =
        StatusModeCardUiState(
            mode = mode,
            title =
                when (mode) {
                    StatusMode.LocalDpiBypass -> "Local bypass"
                    StatusMode.RemoteVpn -> "VPN"
                    StatusMode.Diagnostic -> "Diagnostic Scan"
                },
            primaryLabel =
                primaryLabel
                    ?: when (mode) {
                        StatusMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                        StatusMode.RemoteVpn -> "relay.example"
                        StatusMode.Diagnostic -> "No analysis yet"
                    },
            statusLine =
                when {
                    loading -> "Busy"
                    active -> "Connected 00:01:00"
                    else -> "Inactive"
                },
            primaryActionLabel =
                when {
                    mode == StatusMode.Diagnostic -> "Run Scan"
                    active -> "Disable"
                    else -> "Enable"
                },
            configureLabel = "Configure",
            primaryActionEnabled = primaryActionEnabled,
            summaryFacets = summaryFacets,
            isActive = active,
            isLoading = loading,
        )
}

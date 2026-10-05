package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeAppliedConfigurationUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeAppliedConfigurationPanelTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `pending saved edits require explicit reconnect confirmation and dismiss preserves connection`() {
        var reconnects = 0
        render(
            HomeAppliedConfigurationUiState(
                visible = true,
                status = "Pending",
                pending = true,
                confirmation = confirmation,
            ),
            {
                reconnects++
            },
        )
        composeRule.onNodeWithTag("home_runtime_reconnect").performClick()
        composeRule.onNodeWithText(text(R.string.runtime_config_confirm_body)).assertExists()
        assertEquals(0, reconnects)
        composeRule.onNodeWithText(text(R.string.config_cancel)).performClick()
        assertEquals(0, reconnects)
        composeRule.onNodeWithTag("home_runtime_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("home_runtime_reconnect").performClick()
        composeRule.onNodeWithTag("home_runtime_confirm").performClick()
        assertEquals(1, reconnects)
    }

    @Test
    fun `applying offers cancellation without offering another replacement`() {
        var cancellations = 0
        render(
            HomeAppliedConfigurationUiState(
                visible = true,
                status = "Applying",
                pending = true,
                reconnecting = true,
            ),
            onCancel = {
                cancellations++
            },
        )
        composeRule.onNodeWithTag("home_runtime_reconnect").assertDoesNotExist()
        composeRule.onNodeWithTag("home_runtime_cancel").performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun `failed replacement labels previous facets as last confirmed`() {
        render(
            HomeAppliedConfigurationUiState(
                visible = true,
                status = "Failed",
                confirmedSummary = "Xray · WS · DoH",
                previous = true,
            ),
        )
        composeRule.onNodeWithText(text(R.string.runtime_config_last_confirmed)).assertExists()
        composeRule.onNodeWithText("Xray · WS · DoH").assertExists()
    }

    @Test
    fun `unknown runtime cannot show confirmed facets or reconnect promise`() {
        render(HomeAppliedConfigurationUiState(visible = true, status = "Unknown"))
        composeRule.onNodeWithText(text(R.string.runtime_config_last_confirmed)).assertDoesNotExist()
        composeRule.onNodeWithTag("home_runtime_reconnect").assertDoesNotExist()
    }

    @Test fun `confirmation closes when pending changes disappear`() =
        assertConfirmationInvalidated {
            it.copy(pending = false, confirmation = null)
        }

    @Test fun `confirmation closes when another entry point starts reconnecting`() =
        assertConfirmationInvalidated {
            it.copy(reconnecting = true)
        }

    @Test fun `confirmation closes when mode or runtime ownership changes`() =
        assertConfirmationInvalidated {
            it.copy(
                confirmation =
                    com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime(
                        com.poyka.ripdpi.data.Mode.Proxy,
                        "new-runtime",
                        2,
                    ),
            )
        }

    private fun assertConfirmationInvalidated(
        transform: (HomeAppliedConfigurationUiState) -> HomeAppliedConfigurationUiState,
    ) {
        val initial =
            HomeAppliedConfigurationUiState(
                visible = true,
                status = "Pending",
                pending = true,
                confirmation = confirmation,
            )
        var state by mutableStateOf(initial)
        var reconnects = 0
        composeRule.setContent { RipDpiTheme { HomeAppliedConfigurationPanel(state, { reconnects++ }, {}) } }
        composeRule.onNodeWithTag("home_runtime_reconnect").performClick()
        composeRule.onNodeWithTag("home_runtime_confirm").assertExists()
        composeRule.runOnIdle { state = transform(state) }
        composeRule.onNodeWithTag("home_runtime_confirm").assertDoesNotExist()
        composeRule.runOnIdle { state = initial }
        composeRule.onNodeWithTag("home_runtime_confirm").assertDoesNotExist()
        assertEquals(0, reconnects)
    }

    @Test fun `maximum font LTR keeps complete labels within panel`() = assertMaximumFont(LayoutDirection.Ltr)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun `maximum font Arabic RTL keeps complete labels within panel`() = assertMaximumFont(LayoutDirection.Rtl)

    private fun assertMaximumFont(direction: LayoutDirection) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(411.dp, 839.dp)) {
                        HomeAppliedConfigurationPanel(
                            HomeAppliedConfigurationUiState(
                                visible = true,
                                status = text(R.string.runtime_config_pending),
                                pending = true,
                                confirmation = confirmation,
                            ),
                            {},
                            {},
                        )
                    }
                }
            }
        }
        val panel = composeRule.onNodeWithTag("home_runtime_configuration").fetchSemanticsNode().boundsInRoot
        listOf(
            R.string.runtime_config_title,
            R.string.runtime_config_pending_body,
            R.string.oom_recovery_action_reconnect,
        ).forEach { key ->
            val layouts = mutableListOf<TextLayoutResult>()
            val node =
                composeRule
                    .onNodeWithText(text(key), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    .fetchSemanticsNode()
            val layout = layouts.single()
            val diagnostic =
                "${text(key)} size=${layout.size} width=${layout.didOverflowWidth} " +
                    "height=${layout.didOverflowHeight} lines=${layout.lineCount} " +
                    "node=${node.boundsInRoot} panel=$panel"
            assertFalse(diagnostic, layout.hasVisualOverflow)
            assertTrue(node.boundsInRoot.height + 1f >= layouts.single().size.height)
            assertTrue(node.boundsInRoot.top >= panel.top)
            assertTrue(node.boundsInRoot.bottom <= panel.bottom)
            assertTrue(node.boundsInRoot.left >= panel.left)
            assertTrue(node.boundsInRoot.right <= panel.right)
        }
    }

    @Test fun `failed halted pending configuration keeps information without noop reconnect`() {
        render(HomeAppliedConfigurationUiState(visible = true, status = "Failed", previous = true, pending = true))
        composeRule.onNodeWithText(text(R.string.runtime_config_pending_body)).assertExists()
        composeRule.onNodeWithTag("home_runtime_reconnect").assertDoesNotExist()
    }

    @Test fun `ordinary DNS applying with pending transport offers no competing reconnect`() {
        render(HomeAppliedConfigurationUiState(visible = true, status = "Applying", pending = true))
        composeRule.onNodeWithText(text(R.string.runtime_config_pending_body)).assertExists()
        composeRule.onNodeWithTag("home_runtime_reconnect").assertDoesNotExist()
    }

    private val confirmation =
        com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime(
            com.poyka.ripdpi.data.Mode.VPN,
            "runtime",
            1,
        )

    private fun render(
        state: HomeAppliedConfigurationUiState,
        onReconnect: (com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                HomeAppliedConfigurationPanel(
                    state,
                    onReconnect,
                    onCancel,
                )
            }
        }
    }

    private fun text(key: Int) = RuntimeEnvironment.getApplication().getString(key)
}

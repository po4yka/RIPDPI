package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.ui.components.EnableKeyboardInput
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
@Config(sdk = [35], qualifiers = "ar-w320dp-h640dp-mdpi")
class SimpleHomeActuatorKeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `compact RTL large-font Simple actuator connects once with space`() {
        val toggles = mutableListOf<Boolean>()
        composeRule.setContent {
            EnableKeyboardInput()
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    SimpleHomeContent(
                        pauseState = HomePauseUiState(),
                        onPause = {},
                        onResumePause = {},
                        onStopPause = {},
                        connectionState = ConnectionState.Disconnected,
                        connectionActuator =
                            HomeConnectionActuatorUiState(
                                status = HomeConnectionActuatorStatus.Open,
                                actionLabel = "اتصال",
                            ),
                        diagnostics = HomeDiagnosticsUiState(),
                        activeTransport = null,
                        snackbarHostState = SnackbarHostState(),
                        onToggleConnection = { toggles += it },
                        onRunReport = { error("Connection must not run a report") },
                        onCancelReport = { error("No report is running") },
                        onOpenProfiles = { error("Connection must not open profiles") },
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performScrollTo()
            .assertIsDisplayed()
            .requestFocus()
            .performKeyInput { pressKey(Key.Spacebar) }
        composeRule.runOnIdle { assertEquals(listOf(false), toggles) }
    }
}

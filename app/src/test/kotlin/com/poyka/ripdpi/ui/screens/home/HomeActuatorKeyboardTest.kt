package com.poyka.ripdpi.ui.screens.home

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
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.MainUiState
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
class HomeActuatorKeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `compact RTL large-font Home actuator connects once with enter`() {
        var toggles = 0
        composeRule.setContent {
            EnableKeyboardInput()
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    HomeScreen(
                        uiState =
                            MainUiState(
                                connectionActuator =
                                    HomeConnectionActuatorUiState(
                                        status = HomeConnectionActuatorStatus.Open,
                                        actionLabel = "اتصال",
                                    ),
                            ),
                        onToggleConnection = { toggles++ },
                        onOpenDiagnostics = { error("Connection must not run diagnostics") },
                        onOpenHistory = { error("Connection must not open history") },
                        onRepairPermission = {},
                        onOpenVpnPermissionDialog = {},
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .performScrollTo()
            .assertIsDisplayed()
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }
}

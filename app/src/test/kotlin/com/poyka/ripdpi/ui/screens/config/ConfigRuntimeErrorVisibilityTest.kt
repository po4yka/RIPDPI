package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.ConfigRuntimeOperation
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class ConfigRuntimeErrorVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `VPN rejection reveals its reason and recovery without another user scroll`() {
        val state = mutableStateOf(ConfigUiState())
        val reason = "Grant Android VPN permission before starting the VPN."
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = state.value,
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    onRuntimeModeToggle = { _, _ ->
                        state.value = state.value.copy(runtimeOperation = ConfigRuntimeOperation.Starting)
                    },
                    initialModeSection = ConfigModeSection.Vpn,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).assertIsNotEnabled()

        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    runtimeOperation = null,
                    runtimeError = reason,
                    requiresVpnPermission = true,
                )
        }

        composeRule.onNodeWithText(reason).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnPermissionRecovery).assertIsDisplayed()
    }

    @Test
    fun `local bypass failure reveals its reason without another user scroll`() {
        val state = mutableStateOf(ConfigUiState())
        val reason = "The local proxy could not bind its selected port."
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = state.value,
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    onRuntimeModeToggle = { _, _ ->
                        state.value = state.value.copy(runtimeOperation = ConfigRuntimeOperation.Starting)
                    },
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).assertIsNotEnabled()

        composeRule.runOnIdle {
            state.value = state.value.copy(runtimeOperation = null, runtimeError = reason)
        }

        composeRule.onNodeWithText(reason).assertIsDisplayed()
    }
}

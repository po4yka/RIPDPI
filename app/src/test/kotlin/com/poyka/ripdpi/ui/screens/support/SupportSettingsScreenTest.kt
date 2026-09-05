package com.poyka.ripdpi.ui.screens.support

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.support.RestartPolicyAsk
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class SupportSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun applyStorageFailureKeepsPreviewAndEnabledApplyAction() {
        val context = RuntimeEnvironment.getApplication()
        composeRule.setContent {
            RipDpiTheme {
                SupportSettingsScreen(
                    uiState =
                        SupportSettingsUiState(
                            storageError = true,
                            preview = SupportSettingsPreview("Support", "Fix", emptyList(), RestartPolicyAsk),
                        ),
                    onBack = {},
                    onApply = {},
                    onRetry = {},
                )
            }
        }

        composeRule
            .onNodeWithText(
                context.getString(R.string.asset_provider_persistence_failed_body),
            ).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.support_settings_apply_action))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun storageFailureShowsRetryWithoutAnApplyAction() {
        var retried = false
        val context = RuntimeEnvironment.getApplication()
        composeRule.setContent {
            RipDpiTheme {
                SupportSettingsScreen(
                    uiState = SupportSettingsUiState(storageError = true),
                    onBack = {},
                    onApply = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.config_editor_hydration_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.support_settings_apply_action)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.strategy_config_restore_retry)).performClick()
        assertTrue(retried)
    }
}

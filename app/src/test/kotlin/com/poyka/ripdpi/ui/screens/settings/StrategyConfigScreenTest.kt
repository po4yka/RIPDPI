package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class StrategyConfigScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun textConfigImportAndExportActionsInvokeCallbacks() {
        var importClicks = 0
        var exportClicks = 0

        setScreen(
            onImport = { importClicks += 1 },
            onExport = { exportClicks += 1 },
        )

        composeRule.onNodeWithText("Import").performScrollTo().performClick()
        composeRule.onNodeWithText("Export").performScrollTo().performClick()

        assertEquals(1, importClicks)
        assertEquals(1, exportClicks)
    }

    @Test
    fun savingDisablesTheSubmittedAction() {
        setScreen(isSaving = true)

        composeRule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun recoveryFailureHidesEditorAndExposesExplicitChoices() {
        var retryClicks = 0
        var discardClicks = 0

        setScreen(
            hasHydrationError = true,
            onRetryRecovery = { retryClicks += 1 },
            onDiscardRecovery = { discardClicks += 1 },
        )

        composeRule.onNodeWithText("Import").assertDoesNotExist()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, discardClicks)
        assertEquals(1, retryClicks)
    }

    @Test
    fun retryProgressHidesEditorAndExplainsRecovery() {
        setScreen(isHydrating = true)

        composeRule.onNodeWithText("Restoring saved draft…").assertExists()
        composeRule.onNodeWithText("Import").assertDoesNotExist()
    }

    private fun setScreen(
        onImport: () -> Unit = {},
        onExport: () -> Unit = {},
        isSaving: Boolean = false,
        isHydrating: Boolean = false,
        hasHydrationError: Boolean = false,
        onRetryRecovery: () -> Unit = {},
        onDiscardRecovery: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                StrategyConfigScreen(
                    state =
                        StrategyConfigScreenState(
                            source = StrategyConfigSource.CustomYaml,
                            configText = "version: 1\nstrategies: []\n",
                            luaPath = "",
                            luaFunction = "",
                            activePath = "imported.yaml",
                            banner = null,
                            isSaving = isSaving,
                            isHydrating = isHydrating,
                            hasHydrationError = hasHydrationError,
                        ),
                    onBack = {},
                    onSourceChanged = {},
                    onConfigTextChanged = {},
                    onLuaPathChanged = {},
                    onLuaFunctionChanged = {},
                    onImport = onImport,
                    onExport = onExport,
                    onSave = {},
                    onReload = {},
                    onValidateLua = {},
                    onRetryRecovery = onRetryRecovery,
                    onDiscardRecovery = onDiscardRecovery,
                )
            }
        }
    }
}

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

    private fun setScreen(
        onImport: () -> Unit = {},
        onExport: () -> Unit = {},
        isSaving: Boolean = false,
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
                )
            }
        }
    }
}

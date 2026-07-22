package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
    fun saveFinalizationDisablesEditorAndAllMutableActions() {
        setScreen(isSaving = true, isFinalizingSave = true)

        composeRule.onNodeWithTag(RipDpiTestTags.StrategyConfigSource).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.StrategyConfigEditor).assertIsNotEnabled()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Reload").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Import").performScrollTo().assertIsNotEnabled()
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

    @Test
    @Config(sdk = [35], qualifiers = "ru")
    fun maximumFontStacksRegularActionsWithoutTruncation() {
        setScreen(fontScale = 2f)

        val sourceLayouts = mutableListOf<TextLayoutResult>()
        val sourceText =
            composeRule
                .onNodeWithText("Пользовательский YAML", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(sourceLayouts) }
                .fetchSemanticsNode()
        val sourceField = composeRule.onNodeWithTag(RipDpiTestTags.StrategyConfigSource).fetchSemanticsNode()
        val layout = sourceLayouts.single()
        assertEquals(2, layout.lineCount)
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
        assertTrue(sourceText.boundsInRoot.top >= sourceField.boundsInRoot.top)
        assertTrue(sourceText.boundsInRoot.bottom <= sourceField.boundsInRoot.bottom)
        assertTrue(sourceField.boundsInRoot.height > 48f)

        val actionLabels = listOf("Сохранить", "Перезагрузить", "Импорт", "Экспорт")
        composeRule.onNodeWithText("Экспорт", useUnmergedTree = true).performScrollTo()
        val nodes =
            actionLabels.map { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule
                    .onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                    .fetchSemanticsNode()
                    .also {
                        assertFalse(layouts.single().isLineEllipsized(0))
                    }
            }

        nodes.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.boundsInRoot.bottom <= lower.boundsInRoot.top)
        }
    }

    private fun setScreen(
        onImport: () -> Unit = {},
        onExport: () -> Unit = {},
        isSaving: Boolean = false,
        isFinalizingSave: Boolean = false,
        isHydrating: Boolean = false,
        hasHydrationError: Boolean = false,
        onRetryRecovery: () -> Unit = {},
        onDiscardRecovery: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(411.dp)
                                .height(1_600.dp),
                    ) {
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
                                    isFinalizingSave = isFinalizingSave,
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
    }
}

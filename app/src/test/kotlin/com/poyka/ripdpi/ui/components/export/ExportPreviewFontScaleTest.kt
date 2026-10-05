package com.poyka.ripdpi.ui.components.export

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.ExportPreviewTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
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
@Config(sdk = [35], qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
class ExportPreviewFontScaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `actual RTL dialog keeps actions and complete inventory reachable at font two`() {
        RuntimeEnvironment.setFontScale(2f)
        var confirmed = 0
        var cancelled = 0
        val ready =
            ExportPreviewPresentation.Ready(
                summary = "DNS and HTTP results\n".repeat(60),
                fileName = "ripdpi-report-2026-10-05.zip",
                mimeType = "application/zip",
                byteCount = 12785,
                entryNames = (0 until 100).map { "results/measurement-" + it + ".json" }.toImmutableList(),
                purpose = ExportPreviewPurpose.SaveArchive,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(411.dp, 839.dp)) {
                        ExportPreviewSheet(ready, { confirmed++ }, { cancelled++ }, {})
                    }
                }
            }
        }
        val sheet = composeRule.onNodeWithTag(ExportPreviewTestTags.Sheet).fetchSemanticsNode().boundsInRoot
        assertTrue("Dialog exceeds actual viewport: " + sheet, sheet.width <= 411f && sheet.height <= 839f)
        assertComplete(label(text(R.string.export_preview_save_archive), ExportPreviewTestTags.Confirm))
        assertComplete(label(text(R.string.config_cancel), ExportPreviewTestTags.Cancel))
        composeRule.onNodeWithTag(ExportPreviewTestTags.Content).performScrollToKey("entry-99")
        composeRule.onNodeWithTag(ExportPreviewTestTags.entry(99)).assertIsDisplayed()
        assertComplete(composeRule.onNodeWithTag(ExportPreviewTestTags.entry(99)))
        composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Cancel).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, cancelled)
        }
    }

    private fun label(
        text: String,
        ancestor: String,
    ) = composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ancestor)), useUnmergedTree = true)

    private fun assertComplete(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing actual text layout", layouts.isNotEmpty())
        layouts.forEach {
            assertEquals(2f, it.layoutInput.density.fontScale)
            assertFalse("Clipped text: " + it.layoutInput.text, it.hasVisualOverflow)
        }
    }

    private fun text(key: Int): String = RuntimeEnvironment.getApplication().getString(key)
}

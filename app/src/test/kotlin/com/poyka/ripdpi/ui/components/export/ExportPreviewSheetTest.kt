package com.poyka.ripdpi.ui.components.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.ExportPreviewTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h839dp-mdpi")
class ExportPreviewSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `each purpose hands off only after explicit confirmation`() {
        var ready by mutableStateOf<ExportPreviewPresentation?>(null)
        val confirmed = mutableListOf<ExportPreviewPurpose>()
        var cancelled = 0
        var dismissed = 0
        composeRule.setContent {
            RipDpiTheme {
                ready?.let { presentation ->
                    ExportPreviewSheet(
                        presentation = presentation,
                        onConfirm = {
                            confirmed += (presentation as ExportPreviewPresentation.Ready).purpose
                            ready = null
                        },
                        onCancel = { cancelled++ },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        ExportPreviewPurpose.entries.forEachIndexed { index, purpose ->
            composeRule.runOnIdle { ready = fixture(purpose) }
            composeRule.onNodeWithTag(ExportPreviewTestTags.FileName).assertTextEquals(
                if (purpose == ExportPreviewPurpose.SaveLogs) "ripdpi-log.txt" else "ripdpi-report.zip",
            )
            composeRule.onNodeWithTag(ExportPreviewTestTags.Cancel).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(index, confirmed.size)
                assertEquals(0, cancelled + dismissed)
            }
            composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).assertIsDisplayed().performClick()
            composeRule.onNodeWithTag(ExportPreviewTestTags.Sheet).assertDoesNotExist()
            composeRule.runOnIdle { assertEquals(ExportPreviewPurpose.entries.take(index + 1), confirmed) }
        }
    }

    @Test fun `preparing and error allow cancellation without confirmation`() {
        var presentation by mutableStateOf<ExportPreviewPresentation>(ExportPreviewPresentation.Preparing)
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            RipDpiTheme {
                ExportPreviewSheet(presentation, { confirmed++ }, { cancelled++ }, {})
            }
        }
        composeRule.onNodeWithTag(ExportPreviewTestTags.Preparing).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).assertDoesNotExist()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Cancel).assertIsDisplayed().performClick()
        composeRule.runOnIdle { presentation = ExportPreviewPresentation.Error("Storage unavailable") }
        composeRule.onNodeWithTag(ExportPreviewTestTags.Error).assertTextEquals("Storage unavailable")
        composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).assertDoesNotExist()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Cancel).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(2, cancelled)
        }
    }

    @Test fun `summary action identifies text handoff and log preview identifies excerpt`() {
        var presentation by mutableStateOf(fixture(ExportPreviewPurpose.ShareSummary))
        composeRule.setContent {
            RipDpiTheme { ExportPreviewSheet(presentation, {}, {}, {}) }
        }
        composeRule.onNodeWithText(text(R.string.export_preview_summary_only)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.export_preview_share_summary)).assertIsDisplayed()
        composeRule.runOnIdle { presentation = fixture(ExportPreviewPurpose.SaveLogs) }
        composeRule.onNodeWithText(text(R.string.export_preview_summary_only)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.export_preview_save_logs)).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Content).performScrollToKey("summary")
        composeRule.onNodeWithText(text(R.string.export_preview_log_excerpt).uppercase()).assertIsDisplayed()
    }

    @Test fun `cleanup error has a neutral title and offers no second handoff`() {
        var presentation by mutableStateOf<ExportPreviewPresentation>(fixture(ExportPreviewPurpose.SaveArchive))
        var confirmed = 0
        composeRule.setContent {
            RipDpiTheme {
                ExportPreviewSheet(
                    presentation,
                    onConfirm = {
                        confirmed++
                        presentation = ExportPreviewPresentation.Error(text(R.string.export_preview_cleanup_failed))
                    },
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).performClick()
        composeRule
            .onNodeWithTag(ExportPreviewTestTags.Error)
            .assertTextEquals(text(R.string.export_preview_cleanup_failed))
        composeRule.onNodeWithText(text(R.string.export_preview_error_title).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.export_preview_failed).uppercase()).assertDoesNotExist()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Confirm).assertDoesNotExist()
        composeRule.onNodeWithTag(ExportPreviewTestTags.Cancel).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    private fun fixture(purpose: ExportPreviewPurpose) =
        ExportPreviewPresentation.Ready(
            summary = "DNS: 3 passed\nHTTP: 1 passed",
            fileName = if (purpose == ExportPreviewPurpose.SaveLogs) "ripdpi-log.txt" else "ripdpi-report.zip",
            mimeType = if (purpose == ExportPreviewPurpose.SaveLogs) "text/plain" else "application/zip",
            byteCount = 4096,
            entryNames =
                if (purpose == ExportPreviewPurpose.SaveLogs) {
                    persistentListOf("ripdpi-log.txt")
                } else {
                    persistentListOf("summary.txt", "manifest.json")
                },
            purpose = purpose,
        )

    private fun text(key: Int): String = RuntimeEnvironment.getApplication().getString(key)
}

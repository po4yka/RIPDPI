package com.poyka.ripdpi.ui.components.export

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiActionLayout
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheetCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetScrollPolicy
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.ExportPreviewTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportPreviewSheet(
    presentation: ExportPreviewPresentation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.export_preview_title),
        modifier = modifier.fillMaxHeight(),
        icon = RipDpiIcons.Share,
        testTag = ExportPreviewTestTags.Sheet,
        scrollPolicy = RipDpiSheetScrollPolicy.Content,
        actionLayout = RipDpiActionLayout.Stacked,
        primaryAction = previewConfirmAction(presentation, onConfirm),
        secondaryAction = previewCancelAction(onCancel),
    ) {
        ExportPreviewContent(presentation)
    }
}

@Composable
internal fun ExportPreviewCard(
    presentation: ExportPreviewPresentation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiBottomSheetCard(
        title = stringResource(R.string.export_preview_title),
        modifier = modifier.ripDpiTestTag(ExportPreviewTestTags.Sheet),
        icon = RipDpiIcons.Share,
        scrollPolicy = RipDpiSheetScrollPolicy.Content,
        actionLayout = RipDpiActionLayout.Stacked,
        primaryAction = previewConfirmAction(presentation, onConfirm),
        secondaryAction = previewCancelAction(onCancel),
    ) {
        ExportPreviewContent(presentation)
    }
}

@Composable
private fun previewConfirmAction(
    presentation: ExportPreviewPresentation,
    onConfirm: () -> Unit,
): RipDpiSheetAction? {
    if (presentation !is ExportPreviewPresentation.Ready) return null
    val label =
        when (presentation.purpose) {
            ExportPreviewPurpose.ShareSummary -> R.string.export_preview_share_summary
            ExportPreviewPurpose.ShareArchive -> R.string.export_preview_share_archive
            ExportPreviewPurpose.SaveArchive -> R.string.export_preview_save_archive
            ExportPreviewPurpose.SaveLogs -> R.string.export_preview_save_logs
        }
    return RipDpiSheetAction(
        label = stringResource(label),
        onClick = onConfirm,
        testTag = ExportPreviewTestTags.Confirm,
        wrapLabel = true,
    )
}

@Composable
private fun previewCancelAction(onCancel: () -> Unit) =
    RipDpiSheetAction(
        label = stringResource(R.string.config_cancel),
        onClick = onCancel,
        testTag = ExportPreviewTestTags.Cancel,
        variant = RipDpiButtonVariant.Outline,
        wrapLabel = true,
    )

@Composable
private fun ColumnScope.ExportPreviewContent(presentation: ExportPreviewPresentation) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).ripDpiTestTag(ExportPreviewTestTags.Content),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        when (presentation) {
            ExportPreviewPresentation.Preparing -> {
                item(key = "preparing") {
                    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                        RipDpiSpinner()
                        PreviewText(stringResource(R.string.export_preview_preparing), ExportPreviewTestTags.Preparing)
                    }
                }
            }

            is ExportPreviewPresentation.Error -> {
                item(key = "error") {
                    RipDpiCard {
                        SettingsCategoryHeader(stringResource(R.string.export_preview_error_title))
                        PreviewText(presentation.message, ExportPreviewTestTags.Error)
                    }
                }
            }

            is ExportPreviewPresentation.Ready -> {
                item(key = "artifact") { ExportArtifactCard(presentation) }
                item(key = "privacy") { PreviewText(stringResource(R.string.export_preview_redaction)) }
                if (presentation.purpose == ExportPreviewPurpose.ShareSummary) {
                    item(key = "handoff") { PreviewText(stringResource(R.string.export_preview_summary_only)) }
                }
                item(key = "summary") {
                    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                        SettingsCategoryHeader(
                            stringResource(
                                if (presentation.purpose == ExportPreviewPurpose.SaveLogs) {
                                    R.string.export_preview_log_excerpt
                                } else {
                                    R.string.export_preview_summary
                                },
                            ),
                        )
                        PreviewText(presentation.summary, ExportPreviewTestTags.Summary)
                    }
                }
                item(key = "inventory") {
                    SettingsCategoryHeader(stringResource(R.string.export_preview_contents))
                }
                itemsIndexed(presentation.entryNames, key = { index, _ -> "entry-" + index }) { index, entry ->
                    PreviewText(entry, ExportPreviewTestTags.entry(index), monospace = true)
                }
            }
        }
        item(key = "confirmation") { PreviewText(stringResource(R.string.export_preview_confirm_notice)) }
    }
}

@Composable
private fun ExportArtifactCard(ready: ExportPreviewPresentation.Ready) {
    RipDpiCard {
        SettingsCategoryHeader(stringResource(R.string.export_preview_prepared_file))
        PreviewText(ready.fileName, ExportPreviewTestTags.FileName, monospace = true)
        PreviewText(
            Formatter.formatShortFileSize(LocalContext.current, ready.byteCount),
            ExportPreviewTestTags.ByteCount,
        )
        PreviewText(ready.mimeType, monospace = true)
    }
}

@Composable
private fun PreviewText(
    text: String,
    tag: String? = null,
    monospace: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(tag),
        style = if (monospace) RipDpiThemeTokens.type.monoSmall else RipDpiThemeTokens.type.body,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

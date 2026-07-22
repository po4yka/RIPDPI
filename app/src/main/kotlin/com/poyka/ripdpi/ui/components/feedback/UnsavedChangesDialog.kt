package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
fun UnsavedChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(R.string.unsaved_changes_title),
        dialogTestTag = RipDpiTestTags.UnsavedChangesDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.unsaved_changes_keep_editing),
                onClick = onKeepEditing,
                testTag = RipDpiTestTags.UnsavedChangesKeepEditing,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.unsaved_changes_discard),
                onClick = onDiscard,
                testTag = RipDpiTestTags.UnsavedChangesDiscard,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.unsaved_changes_body),
                tone = RipDpiDialogTone.Destructive,
            ),
    )
}

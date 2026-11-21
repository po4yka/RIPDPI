package com.poyka.ripdpi.ui.screens.awg

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag

/** The destructive "Delete profile" button of a saved AmneziaWG profile, confirmed by a dialog. */
@Composable
internal fun AwgDeleteProfileAction(onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    RipDpiButton(
        text = stringResource(R.string.awg_delete_action),
        onClick = { confirming = true },
        variant = RipDpiButtonVariant.Destructive,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.AwgDeleteAction),
    )
    if (confirming) {
        val cancel = { confirming = false }
        RipDpiDialog(
            onDismissRequest = cancel,
            title = stringResource(R.string.awg_delete_title),
            dismissAction = RipDpiDialogAction(stringResource(R.string.config_cancel), cancel),
            confirmAction =
                RipDpiDialogAction(
                    stringResource(R.string.awg_delete_action),
                    {
                        confirming = false
                        onDelete()
                    },
                    testTag = RipDpiTestTags.AwgDeleteConfirm,
                    destructive = true,
                ),
            visuals = RipDpiDialogVisuals(message = stringResource(R.string.awg_delete_body)),
        )
    }
}

package com.poyka.ripdpi.ui.screens.config

import android.net.Uri
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.UnsavedChangesDialog
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun ModeEditorStartEffect(
    viewModel: ConfigViewModel,
    isLoading: Boolean,
    editingPresetId: String?,
    enabled: Boolean = true,
) {
    LaunchedEffect(viewModel, isLoading, editingPresetId, enabled) {
        if (enabled && !isLoading && editingPresetId == null) {
            viewModel.startEditingPreset()
        }
    }
}

@Composable
internal fun ModeEditorUnsavedChangesDialog(
    visible: Boolean,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (visible) {
        UnsavedChangesDialog(
            onKeepEditing = onKeepEditing,
            onDiscard = onDiscard,
        )
    }
}

@Composable
internal fun ModeEditorHydrationFailureDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_editor_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.config_editor_hydration_failed),
            ),
    )
}

@Composable
internal fun ModeEditorPkcs12Dialog(
    uri: Uri?,
    password: String,
    onPasswordChanged: (String) -> Unit,
    onImport: (Uri, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (uri == null) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_relay_masque_pkcs12_dialog_title),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.config_relay_import),
                onClick = { onImport(uri, password) },
            ),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.config_cancel),
                onClick = onDismiss,
            ),
    ) {
        RipDpiTextField(
            value = password,
            onValueChange = onPasswordChanged,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.config_relay_masque_pkcs12_password),
                    helperText = stringResource(R.string.config_relay_masque_pkcs12_password_helper),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                ),
        )
    }
}

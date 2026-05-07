package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun SettingsPreferenceDialogs(
    localState: SettingsScreenLocalState,
    actions: SettingsScreenActions,
) {
    PinRequiredDialog(
        visible = localState.showPinRequiredDialog,
        onDismiss = { localState.onShowPinRequiredDialogChanged(false) },
    )
    BiometricConfirmDialog(
        visible = localState.showBiometricConfirmDialog,
        onDismiss = { localState.onShowBiometricConfirmDialogChanged(false) },
        onConfirm = {
            localState.onShowBiometricConfirmDialogChanged(false)
            actions.onBiometricChanged(true)
        },
    )
    ResetConfirmDialog(
        visible = localState.showResetConfirmDialog,
        onDismiss = { localState.onShowResetConfirmDialogChanged(false) },
        onConfirm = {
            actions.onResetSettings()
            localState.onShowResetConfirmDialogChanged(false)
        },
    )
}

@Composable
private fun PinRequiredDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_biometric_pin_required_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_biometric_pin_required_ok),
                onClick = onDismiss,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.settings_biometric_pin_required_message),
            ),
    )
}

@Composable
private fun BiometricConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_biometric_confirm_title),
        dialogTestTag = RipDpiTestTags.SettingsBiometricConfirmDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_biometric_confirm_cancel),
                onClick = onDismiss,
                testTag = RipDpiTestTags.SettingsBiometricConfirmCancel,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_biometric_confirm_enable),
                onClick = onConfirm,
                testTag = RipDpiTestTags.SettingsBiometricConfirmEnable,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.settings_biometric_confirm_message),
            ),
    )
}

@Composable
private fun ResetConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_reset_dialog_title),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.settings_reset_dialog_body),
                tone = RipDpiDialogTone.Destructive,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.settings_reset_confirm),
                onClick = onConfirm,
            ),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.config_cancel),
                onClick = onDismiss,
            ),
    )
}

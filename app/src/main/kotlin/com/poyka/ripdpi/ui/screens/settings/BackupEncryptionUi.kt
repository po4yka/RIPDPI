package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.security.BiometricAuthResult
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

private const val MinimumBackupPassphraseLength = 8

internal fun defaultEncryptedBackupFilename(now: Date = Date()): String =
    defaultBackupFilename(now).removeSuffix(".json") + ".ripdpi"

@Composable
internal fun rememberBackupVariantChosen(
    context: Context,
    coroutineScope: CoroutineScope,
    biometricAuthManager: BiometricAuthManager,
    snackbarHostState: SnackbarHostState,
    launchPicker: (BackupVariant) -> Unit,
    onFullAuthenticated: () -> Unit,
    onPickerDismissed: () -> Unit,
): (BackupVariant) -> Unit {
    val biometricTitle = stringResource(R.string.backup_export_biometric_title)
    val biometricSubtitle = stringResource(R.string.backup_export_biometric_subtitle)
    val biometricCancel = stringResource(R.string.backup_export_biometric_cancel)
    val biometricFailedMessage = stringResource(R.string.backup_export_biometric_failed)
    val biometricUnavailableMessage = stringResource(R.string.backup_export_biometric_unavailable)
    return { variant ->
        onPickerDismissed()
        if (variant == BackupVariant.SHARE) {
            launchPicker(variant)
        } else if (biometricAuthManager.canAuthenticate(context) != BiometricManager.BIOMETRIC_SUCCESS) {
            coroutineScope.launch { snackbarHostState.showSnackbar(biometricUnavailableMessage) }
        } else {
            coroutineScope.launch {
                when (
                    biometricAuthManager.authenticate(
                        context as FragmentActivity,
                        biometricTitle,
                        biometricSubtitle,
                        biometricCancel,
                    )
                ) {
                    is BiometricAuthResult.Success -> onFullAuthenticated()

                    is BiometricAuthResult.Cancelled -> Unit

                    is BiometricAuthResult.Error,
                    is BiometricAuthResult.Failed,
                    -> snackbarHostState.showSnackbar(biometricFailedMessage)
                }
            }
        }
    }
}

@Composable
internal fun BackupExportOverlays(
    showVariantPicker: Boolean,
    showPassphraseDialog: Boolean,
    onVariantPickerDismiss: () -> Unit,
    onVariantChosen: (BackupVariant) -> Unit,
    onPassphraseDismiss: () -> Unit,
    onPassphraseConfirmed: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    if (showVariantPicker) {
        BackupVariantPickerSheet(onDismiss = onVariantPickerDismiss, onVariantChosen = onVariantChosen)
    }
    if (showPassphraseDialog) {
        BackupPassphraseDialog(
            title = stringResource(R.string.backup_encryption_create_title),
            message = stringResource(R.string.backup_encryption_create_body),
            passphrase = passphrase,
            onPassphraseChange = { passphrase = it },
            confirmation = confirmation,
            onConfirmationChange = { confirmation = it },
            confirmLabel = stringResource(R.string.backup_encryption_create_action),
            onConfirm = {
                onPassphraseConfirmed(passphrase)
                passphrase = ""
                confirmation = ""
            },
            onDismiss = {
                passphrase = ""
                confirmation = ""
                onPassphraseDismiss()
            },
        )
    }
}

@Composable
internal fun BackupPassphraseDialog(
    title: String,
    message: String,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmation: String? = null,
    onConfirmationChange: (String) -> Unit = {},
) {
    val valid =
        passphrase.length >= MinimumBackupPassphraseLength &&
            (confirmation == null || confirmation == passphrase)
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = title,
        visuals = RipDpiDialogVisuals(message = message),
        confirmAction = RipDpiDialogAction(label = confirmLabel, onClick = onConfirm).takeIf { valid },
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.backup_export_biometric_cancel),
                onClick = onDismiss,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            SecureBackupTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = stringResource(R.string.backup_encryption_passphrase),
            )
            if (confirmation != null) {
                SecureBackupTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    label = stringResource(R.string.backup_encryption_confirm_passphrase),
                    error =
                        stringResource(R.string.backup_encryption_passphrase_mismatch)
                            .takeIf { confirmation.isNotEmpty() && confirmation != passphrase },
                )
            }
        }
    }
}

@Composable
private fun SecureBackupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        decoration = RipDpiTextFieldDecoration(label = label, errorText = error),
        behavior = RipDpiTextFieldBehavior(visualTransformation = PasswordVisualTransformation()),
    )
}

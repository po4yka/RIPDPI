package com.poyka.ripdpi.ui.screens.awg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.screens.relayeditor.RelaySecretField
import com.poyka.ripdpi.ui.screens.relayeditor.fieldErrorText
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun AwgV3Fields(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onRevealHeaderProtectionKey: () -> Unit,
) {
    val editor = uiState.editor
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RipDpiPanelHeader(
            title = stringResource(R.string.awg_v3_title),
            supporting = stringResource(R.string.awg_v3_help),
        )
        RelaySecretField(
            label = stringResource(R.string.awg_field_header_protection_key),
            value = editor.rawText(AwgEditorField.HEADER_PROTECTION_KEY),
            revealed = uiState.headerProtectionKeyRevealed,
            onValueChange = { onFieldChanged(AwgEditorField.HEADER_PROTECTION_KEY, it) },
            onReveal = onRevealHeaderProtectionKey,
            errorText =
                fieldErrorText(
                    editor.rawText(AwgEditorField.HEADER_PROTECTION_KEY),
                    editor.hasFieldError(AwgEditorField.HEADER_PROTECTION_KEY),
                ),
            enabled = !editor.obfuscationLocked,
            readOnly = editor.isManaged(AwgEditorField.HEADER_PROTECTION_KEY),
            testTag = RipDpiTestTags.awgField(AwgEditorField.HEADER_PROTECTION_KEY.name),
        )
        listOf(
            AwgEditorField.CONTENT_PADDING_ADDITION,
            AwgEditorField.REKEY_AFTER_TIME,
            AwgEditorField.REKEY_TIMEOUT,
            AwgEditorField.REJECT_AFTER_TIME,
            AwgEditorField.KEEPALIVE_TIMEOUT,
            AwgEditorField.MAX_HANDSHAKE_ATTEMPTS,
        ).forEach { field ->
            PlainField(field, editor, onFieldChanged, enabled = !editor.obfuscationLocked)
        }
        listOf(AwgEditorField.RANDOM_TRAILERS, AwgEditorField.DISABLE_COOKIES).forEach { field ->
            RipDpiSwitch(
                checked =
                    if (field ==
                        AwgEditorField.RANDOM_TRAILERS
                    ) {
                        editor.form.v3.randomTrailers
                    } else {
                        editor.form.v3.disableCookies
                    },
                onCheckedChange = { onFieldChanged(field, it.toString()) },
                label = stringResource(awgFieldLabel(field)),
                enabled = !editor.obfuscationLocked && !editor.isManaged(field),
                testTag = RipDpiTestTags.awgField(field.name),
            )
        }
    }
}

@Composable
internal fun AwgCohortReplacementDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onCancel,
        title = stringResource(R.string.awg_cohort_replace_title),
        dismissAction = RipDpiDialogAction(stringResource(R.string.config_cancel), onCancel),
        confirmAction =
            RipDpiDialogAction(
                stringResource(R.string.awg_cohort_replace_action),
                onConfirm,
                destructive = true,
            ),
        visuals = RipDpiDialogVisuals(message = stringResource(R.string.awg_cohort_replace_body)),
    )
}

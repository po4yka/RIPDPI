package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun WsTunnelWorkerSettingsUi(
    workerUrl: String,
    credentialRef: String,
    enabled: Boolean,
    onSave: (String, String, String) -> Unit,
    onClear: () -> Unit,
) {
    val configured = workerUrl.isNotBlank() && credentialRef.isNotBlank()
    val spacing = RipDpiThemeTokens.spacing
    var editorVisible by remember { mutableStateOf(false) }

    SettingsRow(
        title = stringResource(R.string.ws_tunnel_worker_transport_label),
        subtitle =
            if (configured) {
                stringResource(R.string.ws_tunnel_worker_transport_configured, workerUrl, credentialRef)
            } else {
                stringResource(R.string.ws_tunnel_worker_transport_not_configured)
            },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RipDpiButton(
            text =
                stringResource(
                    if (configured) {
                        R.string.ws_tunnel_worker_transport_edit
                    } else {
                        R.string.ws_tunnel_worker_transport_configure
                    },
                ),
            onClick = { editorVisible = true },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            variant = RipDpiButtonVariant.Outline,
        )
        if (configured) {
            RipDpiButton(
                text = stringResource(R.string.ws_tunnel_worker_transport_clear),
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }

    if (editorVisible) {
        SecureWindowEffect()
        WsTunnelWorkerEditorDialog(
            workerUrl = workerUrl,
            credentialRef = credentialRef,
            onDismiss = { editorVisible = false },
            onSave = { url, ref, bearer ->
                onSave(url, ref, bearer)
                editorVisible = false
            },
        )
    }
}

@Composable
private fun WsTunnelWorkerEditorDialog(
    workerUrl: String,
    credentialRef: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var url by remember(workerUrl) { mutableStateOf(workerUrl) }
    var ref by remember(credentialRef) { mutableStateOf(credentialRef) }
    var bearer by remember { mutableStateOf("") }
    val canSave = url.isNotBlank() && ref.isNotBlank() && bearer.isNotBlank()

    fun dismissAndClear() {
        bearer = ""
        onDismiss()
    }

    RipDpiDialog(
        onDismissRequest = ::dismissAndClear,
        title = stringResource(R.string.ws_tunnel_worker_transport_dialog_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.config_cancel),
                onClick = ::dismissAndClear,
            ),
        confirmAction =
            if (canSave) {
                RipDpiDialogAction(
                    label = stringResource(R.string.config_save),
                    onClick = {
                        val submittedBearer = bearer
                        bearer = ""
                        onSave(url.trim(), ref.trim(), submittedBearer)
                    },
                )
            } else {
                null
            },
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.ws_tunnel_worker_transport_dialog_message),
                icon = null,
            ),
    ) {
        RipDpiTextField(
            value = url,
            onValueChange = { url = it },
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ws_tunnel_worker_transport_url_label),
                    placeholder = "https://example.workers.dev/relay",
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                ),
        )
        RipDpiTextField(
            value = ref,
            onValueChange = { ref = it },
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ws_tunnel_worker_transport_ref_label),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                ),
        )
        RipDpiTextField(
            value = bearer,
            onValueChange = { bearer = it },
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ws_tunnel_worker_transport_bearer_label),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    visualTransformation = PasswordVisualTransformation(),
                ),
        )
    }
}

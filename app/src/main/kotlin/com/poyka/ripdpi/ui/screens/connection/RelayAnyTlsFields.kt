package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPasswordField

@Composable
internal fun AnyTlsRelayFields(
    draft: ConnectionDraft,
    onPasswordChanged: (String) -> Unit,
    errors: Map<String, String> = emptyMap(),
) {
    RelayPasswordField(
        label = stringResource(R.string.anytls_field_password),
        value = draft.relayAnyTlsPassword,
        onValueChange = onPasswordChanged,
        errorText = validationMessage(errors[ConnectionFieldRelayPassword]),
    )
}

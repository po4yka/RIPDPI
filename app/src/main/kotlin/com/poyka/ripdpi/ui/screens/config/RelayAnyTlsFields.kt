package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun AnyTlsRelayFields(
    draft: ConfigDraft,
    onPasswordChanged: (String) -> Unit,
) {
    RipDpiTextField(
        value = draft.relayAnyTlsPassword,
        onValueChange = onPasswordChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.anytls_field_password),
                helperText = stringResource(R.string.anytls_section_tls_body),
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            ),
    )
}

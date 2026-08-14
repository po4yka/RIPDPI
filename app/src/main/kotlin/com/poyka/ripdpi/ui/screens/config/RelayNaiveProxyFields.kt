package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayNaivePath
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun NaiveProxyRelayFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        value = draft.relayNaiveUsername,
        onValueChange = actions.onRelayNaiveUsernameChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_naive_username),
                helperText = stringResource(R.string.config_relay_naive_username_helper),
            ),
    )
    RipDpiTextField(
        value = draft.relayNaivePassword,
        onValueChange = actions.onRelayNaivePasswordChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_naive_password),
                helperText = stringResource(R.string.config_relay_naive_password_helper),
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            ),
    )
    RipDpiTextField(
        value = draft.relayNaivePath,
        onValueChange = actions.onRelayNaivePathChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_naive_path),
                helperText = stringResource(R.string.config_relay_naive_path_helper),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayNaivePath]),
            ),
    )
}

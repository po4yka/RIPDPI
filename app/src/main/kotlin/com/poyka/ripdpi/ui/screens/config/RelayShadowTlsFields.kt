package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiAutofillPolicy
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState

@Composable
internal fun ShadowTlsRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayShadowTlsInnerProfileId,
                onValueChange = actions.onRelayShadowTlsInnerProfileIdChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_shadowtls_inner_profile_id),
            ),
    )
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayShadowTlsPassword,
                onValueChange = actions.onRelayShadowTlsPasswordChanged,
            ),
        autofillPolicy = RipDpiAutofillPolicy.Password,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_shadowtls_password)),
    )
}

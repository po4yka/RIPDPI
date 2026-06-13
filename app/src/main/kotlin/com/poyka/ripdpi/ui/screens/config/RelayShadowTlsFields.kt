package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun ShadowTlsRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        value = draft.relayShadowTlsInnerProfileId,
        onValueChange = actions.onRelayShadowTlsInnerProfileIdChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_shadowtls_inner_profile_id),
            ),
    )
    RipDpiTextField(
        value = draft.relayShadowTlsPassword,
        onValueChange = actions.onRelayShadowTlsPasswordChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_shadowtls_password)),
    )
}

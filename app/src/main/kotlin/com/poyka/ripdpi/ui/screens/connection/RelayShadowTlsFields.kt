package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun ShadowTlsRelayFields(
    draft: ConnectionDraft,
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

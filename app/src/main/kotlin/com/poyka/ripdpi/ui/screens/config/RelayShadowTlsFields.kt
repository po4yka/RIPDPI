package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
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
        decoration = RipDpiTextFieldDecoration(label = "Inner profile ID"),
    )
    RipDpiTextField(
        value = draft.relayShadowTlsPassword,
        onValueChange = actions.onRelayShadowTlsPasswordChanged,
        decoration = RipDpiTextFieldDecoration(label = "ShadowTLS password"),
    )
}

package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun NaiveProxyRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        value = draft.relayNaiveUsername,
        onValueChange = actions.onRelayNaiveUsernameChanged,
        decoration = RipDpiTextFieldDecoration(label = "NaiveProxy username"),
    )
    RipDpiTextField(
        value = draft.relayNaivePassword,
        onValueChange = actions.onRelayNaivePasswordChanged,
        decoration = RipDpiTextFieldDecoration(label = "NaiveProxy password"),
    )
    RipDpiTextField(
        value = draft.relayNaivePath,
        onValueChange = actions.onRelayNaivePathChanged,
        decoration = RipDpiTextFieldDecoration(label = "HTTP path (optional)"),
    )
}

package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun Obfs4RelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiConfigTextField(
        value = draft.relayPtBridgeLine,
        onValueChange = actions.onRelayPtBridgeLineChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = "Bridge line",
                helperText = "Paste a full obfs4 bridge line from your bridge source.",
            ),
    )
}

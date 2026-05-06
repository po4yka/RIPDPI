package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayFinalmask
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun RelayFinalmaskFragmentFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    RipDpiTextField(
        value = draft.relayFinalmaskFragmentPackets,
        onValueChange = actions.onRelayFinalmaskFragmentPacketsChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = "Fragment packets",
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskFragmentMinBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMinBytesChanged,
        decoration = RipDpiTextFieldDecoration(label = "Fragment min bytes"),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskFragmentMaxBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMaxBytesChanged,
        decoration = RipDpiTextFieldDecoration(label = "Fragment max bytes"),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
}

private fun finalmaskNumericTextFieldBehavior(): RipDpiTextFieldBehavior =
    RipDpiTextFieldBehavior(
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

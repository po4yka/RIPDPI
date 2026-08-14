package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
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
                label = stringResource(R.string.relay_finalmask_fragment_packets_label),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskFragmentMinBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMinBytesChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_finalmask_fragment_min_bytes_label),
            ),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskFragmentMaxBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMaxBytesChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_finalmask_fragment_max_bytes_label),
            ),
        behavior = finalmaskNumericTextFieldBehavior(),
    )
}

private fun finalmaskNumericTextFieldBehavior(): RipDpiTextFieldBehavior =
    RipDpiTextFieldBehavior(
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

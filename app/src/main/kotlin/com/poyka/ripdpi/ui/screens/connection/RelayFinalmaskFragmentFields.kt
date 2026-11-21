package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayNumberField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayValueField

@Composable
internal fun RelayFinalmaskFragmentFields(
    draft: ConnectionDraft,
    uiState: ConnectionUiState,
    actions: RelayFinalmaskActions,
) {
    RelayNumberField(
        field =
            RelayValueField(
                label = stringResource(R.string.relay_finalmask_fragment_packets_label),
                errorText = validationMessage(uiState.validationErrors[ConnectionFieldRelayFinalmask]),
            ),
        value = draft.relayFinalmaskFragmentPackets,
        onValueChange = actions.onRelayFinalmaskFragmentPacketsChanged,
    )
    RelayNumberField(
        field = RelayValueField(label = stringResource(R.string.relay_finalmask_fragment_min_bytes_label)),
        value = draft.relayFinalmaskFragmentMinBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMinBytesChanged,
    )
    RelayNumberField(
        field = RelayValueField(label = stringResource(R.string.relay_finalmask_fragment_max_bytes_label)),
        value = draft.relayFinalmaskFragmentMaxBytes,
        onValueChange = actions.onRelayFinalmaskFragmentMaxBytesChanged,
    )
}

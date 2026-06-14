package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayFinalmask
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun RelayFinalmaskHeaderFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    RipDpiTextField(
        value = draft.relayFinalmaskHeaderHex,
        onValueChange = actions.onRelayFinalmaskHeaderHexChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_finalmask_header_hex_label),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskTrailerHex,
        onValueChange = actions.onRelayFinalmaskTrailerHexChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.relay_finalmask_trailer_hex_label)),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskRandRange,
        onValueChange = actions.onRelayFinalmaskRandRangeChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.relay_finalmask_random_range_label)),
    )
}

@Composable
internal fun RelayFinalmaskSudokuFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    RipDpiTextField(
        value = draft.relayFinalmaskSudokuSeed,
        onValueChange = actions.onRelayFinalmaskSudokuSeedChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_finalmask_sudoku_seed_label),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
    )
}

@Composable
internal fun RelayFinalmaskNoiseFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    RipDpiTextField(
        value = draft.relayFinalmaskRandRange,
        onValueChange = actions.onRelayFinalmaskRandRangeChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_finalmask_noise_range_label),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
    )
}

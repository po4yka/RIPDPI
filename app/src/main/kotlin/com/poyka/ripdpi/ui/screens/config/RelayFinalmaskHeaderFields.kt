package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
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
                label = "Header hex",
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskTrailerHex,
        onValueChange = actions.onRelayFinalmaskTrailerHexChanged,
        decoration = RipDpiTextFieldDecoration(label = "Trailer hex"),
    )
    RipDpiTextField(
        value = draft.relayFinalmaskRandRange,
        onValueChange = actions.onRelayFinalmaskRandRangeChanged,
        decoration = RipDpiTextFieldDecoration(label = "Random range"),
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
                label = "Sudoku seed",
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
                label = "Noise range",
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayFinalmask]),
            ),
    )
}

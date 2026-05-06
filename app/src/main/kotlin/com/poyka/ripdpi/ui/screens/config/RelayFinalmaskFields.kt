package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun RelayFinalmaskFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    val spacing = RipDpiThemeTokens.spacing
    Text(
        text = "Finalmask",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        RelayKindChip(draft.relayFinalmaskType, RelayFinalmaskTypeOff, "Off", actions.onRelayFinalmaskTypeChanged)
        RelayKindChip(
            draft.relayFinalmaskType,
            RelayFinalmaskTypeHeaderCustom,
            "Header",
            actions.onRelayFinalmaskTypeChanged,
        )
        RelayKindChip(draft.relayFinalmaskType, RelayFinalmaskTypeSudoku, "Sudoku", actions.onRelayFinalmaskTypeChanged)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        RelayKindChip(
            draft.relayFinalmaskType,
            RelayFinalmaskTypeFragment,
            "Fragment",
            actions.onRelayFinalmaskTypeChanged,
        )
        RelayKindChip(draft.relayFinalmaskType, RelayFinalmaskTypeNoise, "Noise", actions.onRelayFinalmaskTypeChanged)
    }
    when (draft.relayFinalmaskType) {
        RelayFinalmaskTypeHeaderCustom -> RelayFinalmaskHeaderFields(draft, uiState, actions)
        RelayFinalmaskTypeSudoku -> RelayFinalmaskSudokuFields(draft, uiState, actions)
        RelayFinalmaskTypeFragment -> RelayFinalmaskFragmentFields(draft, uiState, actions)
        RelayFinalmaskTypeNoise -> RelayFinalmaskNoiseFields(draft, uiState, actions)
    }
}

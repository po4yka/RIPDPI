package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val finalmaskHelpWeight = 1.7f

@Composable
internal fun RelayFinalmaskFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayFinalmaskActions,
) {
    val spacing = RipDpiThemeTokens.spacing
    Text(
        text = stringResource(R.string.config_relay_finalmask_title),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        finalmaskOptions().forEach { option ->
            FinalmaskOptionRow(
                option = option,
                selectedKind = draft.relayFinalmaskType,
                onRelayFinalmaskTypeChanged = actions.onRelayFinalmaskTypeChanged,
            )
        }
    }
    when (draft.relayFinalmaskType) {
        RelayFinalmaskTypeHeaderCustom -> RelayFinalmaskHeaderFields(draft, uiState, actions)
        RelayFinalmaskTypeSudoku -> RelayFinalmaskSudokuFields(draft, uiState, actions)
        RelayFinalmaskTypeFragment -> RelayFinalmaskFragmentFields(draft, uiState, actions)
        RelayFinalmaskTypeNoise -> RelayFinalmaskNoiseFields(draft, uiState, actions)
    }
}

@Composable
private fun FinalmaskOptionRow(
    option: FinalmaskOption,
    selectedKind: String,
    onRelayFinalmaskTypeChanged: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinalmaskOptionChip(
            option = option,
            selectedKind = selectedKind,
            onRelayFinalmaskTypeChanged = onRelayFinalmaskTypeChanged,
        )
        Text(
            text = stringResource(option.helpRes),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
            modifier = Modifier.weight(finalmaskHelpWeight),
        )
    }
}

@Composable
private fun RowScope.FinalmaskOptionChip(
    option: FinalmaskOption,
    selectedKind: String,
    onRelayFinalmaskTypeChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(option.labelRes),
        selected = selectedKind == option.kind,
        onClick = { onRelayFinalmaskTypeChanged(option.kind) },
        modifier = Modifier.weight(1f),
    )
}

private data class FinalmaskOption(
    val kind: String,
    val labelRes: Int,
    val helpRes: Int,
)

private fun finalmaskOptions(): List<FinalmaskOption> =
    listOf(
        FinalmaskOption(
            kind = RelayFinalmaskTypeOff,
            labelRes = R.string.config_relay_finalmask_off,
            helpRes = R.string.config_relay_finalmask_off_help,
        ),
        FinalmaskOption(
            kind = RelayFinalmaskTypeHeaderCustom,
            labelRes = R.string.config_relay_finalmask_header,
            helpRes = R.string.config_relay_finalmask_header_help,
        ),
        FinalmaskOption(
            kind = RelayFinalmaskTypeSudoku,
            labelRes = R.string.config_relay_finalmask_sudoku,
            helpRes = R.string.config_relay_finalmask_sudoku_help,
        ),
        FinalmaskOption(
            kind = RelayFinalmaskTypeFragment,
            labelRes = R.string.config_relay_finalmask_fragment,
            helpRes = R.string.config_relay_finalmask_fragment_help,
        ),
        FinalmaskOption(
            kind = RelayFinalmaskTypeNoise,
            labelRes = R.string.config_relay_finalmask_noise,
            helpRes = R.string.config_relay_finalmask_noise_help,
        ),
    )

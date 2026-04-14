package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.FakeOrderDefault
import com.poyka.ripdpi.data.FakeSeqModeDuplicate
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun FakeOrderingProfileCard(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    fakeOrderOptions: ImmutableList<RipDpiDropdownOption<String>>,
    fakeSeqModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = uiState.desync.primaryFakeOrderingStep ?: return
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val supportsVisualEditing = uiState.desync.fakeOrderingVisualEditorSupported
    val baseEnabled = visualEditorEnabled && supportsVisualEditing
    val hostfakeOrderEditable = step.kind != TcpChainStepKind.HostFake || step.midhostMarker.isNotBlank()
    val hasOverrides = step.fakeOrder != FakeOrderDefault || step.fakeSeqMode != FakeSeqModeDuplicate

    RipDpiCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.fake_order_card_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text =
                if (supportsVisualEditing) {
                    stringResource(R.string.fake_order_card_body)
                } else {
                    stringResource(R.string.fake_order_card_dsl_only)
                },
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.fake_order_card_step_label),
                value = step.kind.wireName,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_order_card_runtime_label),
                value =
                    if (hasOverrides) {
                        stringResource(R.string.fake_order_card_runtime_custom)
                    } else {
                        stringResource(R.string.fake_order_card_runtime_default)
                    },
            )
        }
        if (supportsVisualEditing) {
            AdvancedDropdownSetting(
                title = stringResource(R.string.fake_order_card_order_title),
                description =
                    if (hostfakeOrderEditable) {
                        stringResource(R.string.fake_order_card_order_body)
                    } else {
                        stringResource(R.string.fake_order_card_hostfake_order_locked)
                    },
                value = step.fakeOrder,
                options = fakeOrderOptions,
                setting = AdvancedOptionSetting.FakeOrder,
                onSelected = onOptionSelected,
                enabled = baseEnabled && hostfakeOrderEditable,
                showDivider = true,
            )
            AdvancedDropdownSetting(
                title = stringResource(R.string.fake_order_card_seqmode_title),
                description = stringResource(R.string.fake_order_card_seqmode_body),
                value = step.fakeSeqMode,
                options = fakeSeqModeOptions,
                setting = AdvancedOptionSetting.FakeSeqMode,
                onSelected = onOptionSelected,
                enabled = baseEnabled,
            )
        }
    }
}

package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCongestionControlCubic
import com.poyka.ripdpi.ui.components.inputs.RipDpiAutofillPolicy
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun TuicRelayFields(
    draft: ConfigDraft,
    actions: RelayTuicActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayTuicUuid,
                onValueChange = actions.onRelayTuicUuidChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_tuic_uuid)),
    )
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayTuicPassword,
                onValueChange = actions.onRelayTuicPasswordChanged,
            ),
        autofillPolicy = RipDpiAutofillPolicy.Password,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_tuic_password)),
    )
    RipDpiSwitch(
        checked = draft.relayTuicZeroRtt,
        onCheckedChange = actions.onRelayTuicZeroRttChanged,
        label = stringResource(R.string.config_relay_tuic_zero_rtt),
    )
    Text(
        text = stringResource(R.string.config_relay_tuic_congestion_control),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RelayKindChip(
            selectedKind = draft.relayTuicCongestionControl,
            kind = RelayCongestionControlBbr,
            labelRes = R.string.config_relay_tuic_congestion_bbr,
            onRelayKindChanged = actions.onRelayTuicCongestionControlChanged,
        )
        RelayKindChip(
            selectedKind = draft.relayTuicCongestionControl,
            kind = RelayCongestionControlCubic,
            labelRes = R.string.config_relay_tuic_congestion_cubic,
            onRelayKindChanged = actions.onRelayTuicCongestionControlChanged,
        )
    }
}

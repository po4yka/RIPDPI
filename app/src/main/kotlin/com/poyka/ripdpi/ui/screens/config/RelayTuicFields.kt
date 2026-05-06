package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCongestionControlCubic
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun TuicRelayFields(
    draft: ConfigDraft,
    actions: RelayTuicActions,
) {
    RipDpiTextField(
        value = draft.relayTuicUuid,
        onValueChange = actions.onRelayTuicUuidChanged,
        decoration = RipDpiTextFieldDecoration(label = "TUIC UUID"),
    )
    RipDpiTextField(
        value = draft.relayTuicPassword,
        onValueChange = actions.onRelayTuicPasswordChanged,
        decoration = RipDpiTextFieldDecoration(label = "TUIC password"),
    )
    RipDpiSwitch(
        checked = draft.relayTuicZeroRtt,
        onCheckedChange = actions.onRelayTuicZeroRttChanged,
        label = "Enable 0-RTT",
    )
    Text(
        text = "Congestion control",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RelayKindChip(
            selectedKind = draft.relayTuicCongestionControl,
            kind = RelayCongestionControlBbr,
            label = "BBR",
            onRelayKindChanged = actions.onRelayTuicCongestionControlChanged,
        )
        RelayKindChip(
            selectedKind = draft.relayTuicCongestionControl,
            kind = RelayCongestionControlCubic,
            label = "CUBIC",
            onRelayKindChanged = actions.onRelayTuicCongestionControlChanged,
        )
    }
}

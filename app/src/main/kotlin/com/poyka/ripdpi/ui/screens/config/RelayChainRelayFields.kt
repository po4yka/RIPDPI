package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun RelayChainFields(
    draft: ConfigDraft,
    onRelayChainEntryProfileIdChanged: (String) -> Unit,
    onRelayChainExitProfileIdChanged: (String) -> Unit,
) {
    Text(
        text = "Chain relay uses saved relay profiles for both hops. Legacy inline chain settings are read-only.",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    RipDpiTextField(
        value = draft.relayChainEntryProfileId,
        onValueChange = onRelayChainEntryProfileIdChanged,
        decoration = RipDpiTextFieldDecoration(label = "Entry profile ID"),
    )
    RipDpiTextField(
        value = draft.relayChainExitProfileId,
        onValueChange = onRelayChainExitProfileIdChanged,
        decoration = RipDpiTextFieldDecoration(label = "Exit profile ID"),
    )
}

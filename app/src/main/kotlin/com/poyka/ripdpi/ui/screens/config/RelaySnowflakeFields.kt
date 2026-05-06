package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun SnowflakeRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        value = draft.relaySnowflakeBrokerUrl,
        onValueChange = actions.onRelaySnowflakeBrokerUrlChanged,
        decoration = RipDpiTextFieldDecoration(label = "Broker URL"),
    )
    RipDpiTextField(
        value = draft.relaySnowflakeFrontDomain,
        onValueChange = actions.onRelaySnowflakeFrontDomainChanged,
        decoration = RipDpiTextFieldDecoration(label = "Front domain"),
    )
}

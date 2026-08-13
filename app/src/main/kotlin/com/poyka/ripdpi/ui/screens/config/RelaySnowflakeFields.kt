package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState

@Composable
internal fun SnowflakeRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relaySnowflakeBrokerUrl,
                onValueChange = actions.onRelaySnowflakeBrokerUrlChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.relay_snowflake_broker_url_label)),
    )
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relaySnowflakeFrontDomain,
                onValueChange = actions.onRelaySnowflakeFrontDomainChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.relay_snowflake_front_domain_label)),
    )
}

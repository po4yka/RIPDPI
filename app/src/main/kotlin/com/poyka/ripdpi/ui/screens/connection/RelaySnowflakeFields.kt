package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.relayeditor.RelayHostField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayUrlField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayValueField

@Composable
internal fun SnowflakeRelayFields(
    draft: ConnectionDraft,
    actions: RelayMiscKindActions,
) {
    RelayUrlField(
        field = RelayValueField(label = stringResource(R.string.relay_snowflake_broker_url_label)),
        value = draft.relaySnowflakeBrokerUrl,
        onValueChange = actions.onRelaySnowflakeBrokerUrlChanged,
    )
    RelayHostField(
        field = RelayValueField(label = stringResource(R.string.relay_snowflake_front_domain_label)),
        value = draft.relaySnowflakeFrontDomain,
        onValueChange = actions.onRelaySnowflakeFrontDomainChanged,
    )
}

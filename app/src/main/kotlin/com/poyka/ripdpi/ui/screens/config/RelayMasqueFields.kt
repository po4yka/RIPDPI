package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState

@Composable
internal fun MasqueRelayFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayMasqueActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayMasqueUrl,
                onValueChange = actions.onRelayMasqueUrlChanged,
            ),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_masque_url)),
    )
    MasqueAuthModeRow(draft = draft, uiState = uiState, actions = actions)
    MasquePrivacyPassStatus(uiState)
    if (draft.showsMasqueTokenField()) {
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = draft.relayMasqueAuthToken,
                    onValueChange = actions.onRelayMasqueAuthTokenChanged,
                ),
            decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_masque_token)),
        )
    }
    if (draft.relayMasqueAuthMode == RelayMasqueAuthModeCloudflareMtls) {
        MasqueCloudflareMtlsFields(draft = draft, uiState = uiState, actions = actions)
    }
    RipDpiSwitch(
        checked = draft.relayMasqueUseHttp2Fallback,
        onCheckedChange = actions.onRelayMasqueUseHttp2FallbackChanged,
        label = stringResource(R.string.config_relay_masque_http2),
    )
}

private fun ConfigDraft.showsMasqueTokenField(): Boolean =
    relayMasqueAuthMode != RelayMasqueAuthModePrivacyPass &&
        relayMasqueAuthMode != RelayMasqueAuthModeCloudflareMtls

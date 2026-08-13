package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayCloudflarePublishOrigin
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun CloudflareTunnelRelayFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayVlessActions,
) {
    Text(
        text = stringResource(R.string.relay_cloudflare_tunnel_caption),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Text(
        text = stringResource(R.string.relay_cloudflare_tunnel_mode_label),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RelayKindChip(
            selectedKind = draft.relayCloudflareTunnelMode,
            kind = RelayCloudflareTunnelModeConsumeExisting,
            label = stringResource(R.string.relay_cloudflare_mode_consume_existing),
            onRelayKindChanged = actions.onRelayCloudflareTunnelModeChanged,
        )
        RelayKindChip(
            selectedKind = draft.relayCloudflareTunnelMode,
            kind = RelayCloudflareTunnelModePublishLocalOrigin,
            label = stringResource(R.string.relay_cloudflare_mode_publish_local),
            onRelayKindChanged = actions.onRelayCloudflareTunnelModeChanged,
        )
    }
    if (draft.relayCloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin) {
        CloudflareLocalOriginFields(draft = draft, uiState = uiState, actions = actions)
    }
    VlessTransportFields(draft = draft, actions = actions)
    VlessUuidField(draft = draft, actions = actions)
}

@Composable
private fun CloudflareLocalOriginFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayVlessActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayCloudflarePublishLocalOriginUrl,
                onValueChange = actions.onRelayCloudflarePublishLocalOriginUrlChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_cloudflare_local_origin_url_label),
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayCloudflarePublishOrigin]),
            ),
    )
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayCloudflareCredentialsRef,
                onValueChange = actions.onRelayCloudflareCredentialsRefChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_cloudflare_credentials_reference_label),
            ),
    )
    RipDpiConfigTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayCloudflareTunnelToken,
                onValueChange = actions.onRelayCloudflareTunnelTokenChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_cloudflare_tunnel_token_label),
                helperText = stringResource(R.string.relay_cloudflare_tunnel_token_helper),
            ),
    )
    RipDpiConfigTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayCloudflareTunnelCredentialsJson,
                onValueChange = actions.onRelayCloudflareTunnelCredentialsJsonChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_cloudflare_named_tunnel_credentials_label),
            ),
        lineLimits = TextFieldLineLimits.MultiLine(),
    )
}

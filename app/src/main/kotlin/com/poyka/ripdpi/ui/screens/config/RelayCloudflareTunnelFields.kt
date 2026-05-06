package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayCloudflarePublishOrigin
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun CloudflareTunnelRelayFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayVlessActions,
) {
    Text(
        text = "Cloudflare Tunnel publishes or consumes an origin before VLESS transport handling.",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Text(
        text = "Tunnel mode",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RelayKindChip(
            selectedKind = draft.relayCloudflareTunnelMode,
            kind = RelayCloudflareTunnelModeConsumeExisting,
            label = "Consume existing",
            onRelayKindChanged = actions.onRelayCloudflareTunnelModeChanged,
        )
        RelayKindChip(
            selectedKind = draft.relayCloudflareTunnelMode,
            kind = RelayCloudflareTunnelModePublishLocalOrigin,
            label = "Publish local",
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
        value = draft.relayCloudflarePublishLocalOriginUrl,
        onValueChange = actions.onRelayCloudflarePublishLocalOriginUrlChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = "Local origin URL",
                errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayCloudflarePublishOrigin]),
            ),
    )
    RipDpiTextField(
        value = draft.relayCloudflareCredentialsRef,
        onValueChange = actions.onRelayCloudflareCredentialsRefChanged,
        decoration = RipDpiTextFieldDecoration(label = "Credentials reference"),
    )
    RipDpiConfigTextField(
        value = draft.relayCloudflareTunnelToken,
        onValueChange = actions.onRelayCloudflareTunnelTokenChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = "Tunnel token",
                helperText = "Import a Cloudflare tunnel token or credentials JSON.",
            ),
    )
    RipDpiConfigTextField(
        value = draft.relayCloudflareTunnelCredentialsJson,
        onValueChange = actions.onRelayCloudflareTunnelCredentialsJsonChanged,
        decoration = RipDpiTextFieldDecoration(label = "Named tunnel credentials JSON"),
        multiline = true,
    )
}

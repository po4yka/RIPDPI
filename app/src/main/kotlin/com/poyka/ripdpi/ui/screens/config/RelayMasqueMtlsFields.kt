package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun MasqueCloudflareMtlsFields(
    draft: ConfigDraft,
    actions: RelayMasqueActions,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiConfigTextField(
        value = draft.relayMasqueClientCertificateChainPem,
        onValueChange = actions.onRelayMasqueClientCertificateChainPemChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_masque_client_certificate_chain),
                helperText = stringResource(R.string.config_relay_masque_client_certificate_chain_helper),
            ),
        multiline = true,
    )
    RipDpiConfigTextField(
        value = draft.relayMasqueClientPrivateKeyPem,
        onValueChange = actions.onRelayMasqueClientPrivateKeyPemChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_masque_client_private_key),
                helperText = stringResource(R.string.config_relay_masque_client_private_key_helper),
            ),
        multiline = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RipDpiButton(
            text = stringResource(R.string.config_relay_masque_import_certificate_chain),
            onClick = actions.onRelayMasqueImportCertificateChainClicked,
            modifier = Modifier.weight(1f),
            variant = RipDpiButtonVariant.Outline,
        )
        RipDpiButton(
            text = stringResource(R.string.config_relay_masque_import_private_key),
            onClick = actions.onRelayMasqueImportPrivateKeyClicked,
            modifier = Modifier.weight(1f),
            variant = RipDpiButtonVariant.Outline,
        )
    }
    RipDpiButton(
        text = stringResource(R.string.config_relay_masque_import_pkcs12),
        onClick = actions.onRelayMasqueImportPkcs12Clicked,
        modifier = Modifier.fillMaxWidth(),
        variant = RipDpiButtonVariant.Outline,
    )
    RipDpiSwitch(
        checked = draft.relayMasqueCloudflareGeohashEnabled,
        onCheckedChange = actions.onRelayMasqueCloudflareGeohashEnabledChanged,
        label = stringResource(R.string.config_relay_masque_cloudflare_geohash_enabled),
    )
    Text(
        text = stringResource(R.string.config_relay_masque_cloudflare_geohash_helper),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

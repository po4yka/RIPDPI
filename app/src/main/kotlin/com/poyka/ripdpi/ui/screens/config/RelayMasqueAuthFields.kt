package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun MasqueAuthModeRow(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: RelayMasqueActions,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        MasqueAuthModeChip(
            draft.relayMasqueAuthMode,
            RelayMasqueAuthModeBearer,
            R.string.config_relay_masque_auth_bearer,
            actions.onRelayMasqueAuthModeChanged,
        )
        MasqueAuthModeChip(
            draft.relayMasqueAuthMode,
            RelayMasqueAuthModePreshared,
            R.string.config_relay_masque_auth_preshared,
            actions.onRelayMasqueAuthModeChanged,
        )
        MasqueAuthModeChip(
            draft.relayMasqueAuthMode,
            RelayMasqueAuthModeCloudflareMtls,
            R.string.config_relay_masque_auth_cloudflare_direct,
            actions.onRelayMasqueAuthModeChanged,
        )
        if (uiState.supportsMasquePrivacyPass) {
            MasqueAuthModeChip(
                draft.relayMasqueAuthMode,
                RelayMasqueAuthModePrivacyPass,
                R.string.config_relay_masque_auth_privacy_pass,
                actions.onRelayMasqueAuthModeChanged,
            )
        }
    }
}

@Composable
internal fun MasquePrivacyPassStatus(uiState: ConfigUiState) {
    val statusText =
        if (!uiState.supportsMasquePrivacyPass) {
            when (uiState.masquePrivacyPassBuildStatus) {
                MasquePrivacyPassBuildStatus.Available -> {
                    stringResource(R.string.config_relay_masque_privacy_pass_available)
                }

                MasquePrivacyPassBuildStatus.MissingProviderUrl -> {
                    stringResource(R.string.config_relay_masque_privacy_pass_missing_provider)
                }

                MasquePrivacyPassBuildStatus.InvalidProviderUrl -> {
                    stringResource(R.string.config_relay_masque_privacy_pass_invalid_provider)
                }
            }
        } else {
            stringResource(R.string.config_relay_masque_privacy_pass_available)
        }
    Text(
        text = statusText,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun RowScope.MasqueAuthModeChip(
    selectedMode: String,
    mode: String,
    labelRes: Int,
    onRelayMasqueAuthModeChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedMode == mode,
        onClick = { onRelayMasqueAuthModeChanged(mode) },
        modifier = Modifier.weight(1f),
    )
}

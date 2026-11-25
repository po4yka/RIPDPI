package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

@Composable
internal fun ConnectionSavedConnectionCard(
    uiState: ConnectionUiState,
    onChangeProfile: () -> Unit,
    onAddServer: () -> Unit,
) {
    val selectedPreset = uiState.presets.firstOrNull { it.isSelected }
    val presetName = stringResource(titleResForPreset(selectedPreset?.kind ?: ConnectionPresetKind.Custom))
    val serverName =
        when {
            uiState.activeExternalProvider != null -> {
                uiState.activeExternalProvider.displayName
            }

            uiState.activeRelayEnabled -> {
                uiState.vpnProfiles.firstOrNull { it.id == uiState.activeRelayProfileId }?.selectorLabel
                    ?: uiState.activeRelayProfileId
            }

            else -> {
                null
            }
        }
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionSummary),
    ) {
        SettingsRow(
            // No scope or path here: the two controls under this card show them.
            title = serverName ?: presetName,
            subtitle = presetName.takeIf { serverName != null },
            onClick = onChangeProfile,
            showChevron = true,
            testTag = RipDpiTestTags.ConnectionProfileChange,
            showDivider = true,
        )
        SettingsRow(
            title = stringResource(R.string.config_vpn_add_server),
            onClick = onAddServer,
            leadingIcon = RipDpiIcons.Add,
            testTag = RipDpiTestTags.ConnectionAddServer,
        )
    }
}

@Composable
internal fun ConnectionCliOverrideBanner(uiState: ConnectionUiState) {
    // The selected preset holds saved settings; uiState.draft may hold an unsaved editor draft.
    if (uiState.presets
            .firstOrNull { it.isSelected }
            ?.draft
            ?.useCommandLineSettings == true
    ) {
        WarningBanner(
            title = stringResource(R.string.config_cli_banner_title),
            message = stringResource(R.string.config_cli_active_body),
            tone = WarningBannerTone.Restricted,
            testTag = RipDpiTestTags.ConnectionCliOverrideBanner,
        )
    }
}

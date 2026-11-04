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

@Composable
internal fun ConnectionSavedConnectionCard(
    uiState: ConnectionUiState,
    onChangeProfile: () -> Unit,
) {
    val selectedPreset = uiState.presets.firstOrNull { it.isSelected }
    val profileName =
        when {
            uiState.activeExternalProvider != null -> {
                uiState.activeExternalProvider.displayName
            }

            uiState.activeRelayEnabled -> {
                uiState.vpnProfiles.firstOrNull { it.id == uiState.activeRelayProfileId }?.selectorLabel
                    ?: uiState.activeRelayProfileId
            }

            else -> {
                stringResource(titleResForPreset(selectedPreset?.kind ?: ConnectionPresetKind.Custom))
            }
        }
    val scope = stringResource(modeLabelRes(uiState.activeMode))
    val path =
        stringResource(
            if (uiState.usesRelayPath) {
                R.string.config_path_relay
            } else {
                R.string.config_path_direct
            },
        )
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionSummary),
    ) {
        SettingsRow(
            title = profileName,
            subtitle = "$scope · $path",
            onClick = onChangeProfile,
            showChevron = true,
            testTag = RipDpiTestTags.ConnectionProfileChange,
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

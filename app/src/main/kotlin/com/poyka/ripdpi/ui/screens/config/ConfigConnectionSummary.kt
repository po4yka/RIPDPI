package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ConfigSavedConnectionCard(
    uiState: ConfigUiState,
    profilesExpanded: Boolean,
    onChangeProfile: () -> Unit,
) {
    val selectedPreset = uiState.presets.firstOrNull { it.isSelected }
    val profileName =
        if (uiState.activeRelayEnabled) {
            uiState.vpnProfiles.firstOrNull { it.id == uiState.activeRelayProfileId }?.selectorLabel
                ?: uiState.activeRelayProfileId
        } else {
            stringResource(titleResForPreset(selectedPreset?.kind ?: ConfigPresetKind.Custom))
        }
    val path =
        if (uiState.activeRelayEnabled) {
            stringResource(R.string.config_connection_path_relay, profileName)
        } else {
            stringResource(R.string.config_connection_path_direct)
        }
    val expansionState =
        stringResource(if (profilesExpanded) R.string.semantic_state_expanded else R.string.semantic_state_collapsed)
    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigConnectionSummary)) {
        Text(
            text = stringResource(R.string.config_saved_profile_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = profileName,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigSavedProfile),
            style = RipDpiThemeTokens.type.cardTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text =
                stringResource(
                    if (uiState.activeMode == Mode.VPN) {
                        R.string.config_connection_scope_vpn
                    } else {
                        R.string.config_connection_scope_proxy
                    },
                ),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = path,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.foreground,
        )
        RipDpiButton(
            text = stringResource(R.string.config_profile_change),
            onClick = onChangeProfile,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ConfigProfileChange)
                    .semantics { stateDescription = expansionState },
            variant = RipDpiButtonVariant.Outline,
            leadingIcon = RipDpiIcons.Settings,
        )
    }
}

@Composable
internal fun ConfigPrimaryRuntimeAction(
    uiState: ConfigUiState,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit,
    callbacks: ConfigRuntimeCallbacks,
) {
    val runningMode = uiState.runningMode
    val connected = runningMode != null
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        RipDpiButton(
            text =
                stringResource(
                    uiState.runtimeOperation?.labelRes()
                        ?: if (connected) R.string.config_connection_stop else R.string.config_connection_start,
                ),
            onClick = { onRuntimeModeToggle(runningMode ?: uiState.activeMode, !connected) },
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ConfigRuntimeAction),
            // Same treatment as the Home actuator: filled to connect, outlined to disconnect.
            variant = if (connected) RipDpiButtonVariant.Outline else RipDpiButtonVariant.Primary,
            enabled = uiState.runtimeOperation == null,
            loading = uiState.runtimeOperation != null,
        )
        ConfigRuntimeFeedback(uiState, callbacks.requestVpnPermission, callbacks.dismissError)
    }
}

@Composable
internal fun ConfigConnectionGuidance(
    uiState: ConfigUiState,
    section: ConfigModeSection,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val savedDraft = uiState.presets.firstOrNull { it.isSelected }?.draft
    RipDpiAccordion(
        title = stringResource(R.string.config_connection_details),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        headerTestTag = RipDpiTestTags.ConfigConnectionDetails,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            if (savedDraft?.useCommandLineSettings == true) {
                WarningBanner(
                    title = stringResource(R.string.config_cli_banner_title),
                    message = stringResource(R.string.config_cli_banner_body),
                    tone = WarningBannerTone.Restricted,
                )
            }
            Text(
                text =
                    stringResource(
                        if (section == ConfigModeSection.LocalBypass) {
                            R.string.config_local_bypass_simple_body
                        } else {
                            R.string.config_vpn_simple_body
                        },
                    ),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            if (section == ConfigModeSection.LocalBypass) {
                LocalBypassStrategyRow()
                LocalBypassPrecedenceRow(commandLineOverridesEnabled = savedDraft?.useCommandLineSettings == true)
            }
        }
    }
}

package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.EndpointSettingsRow
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.AdvancedSection
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun LocalBypassConfigScreen(
    uiState: ConfigUiState,
    desyncSummary: String,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit,
    onOpenDesyncSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRetestStrategies: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestVpnPermission: () -> Unit = {},
    onDismissRuntimeError: () -> Unit = {},
    settingsOnly: Boolean = false,
) {
    TrackRecomposition("LocalBypassConfigScreen")

    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.home_mode_local_dpi_bypass))
        if (settingsOnly) {
            LocalBypassAdvancedRows(
                uiState,
                desyncSummary,
                onOpenDesyncSettings,
                onOpenDnsSettings,
                showGuidance = false,
            )
            RipDpiButton(
                text = stringResource(R.string.config_local_bypass_retest_strategies),
                onClick = onRetestStrategies,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassRetest),
                variant = RipDpiButtonVariant.Outline,
                leadingIcon = RipDpiIcons.Refresh,
            )
            return@Column
        }
        LocalBypassSimpleCard(
            uiState = uiState,
            onRequestVpnPermission = onRequestVpnPermission,
            onDismissRuntimeError = onDismissRuntimeError,
            onRuntimeModeToggle = onRuntimeModeToggle,
            onRetestStrategies = onRetestStrategies,
        )
        AdvancedSection(initiallyExpanded = uiState.uiPersona == "advanced") {
            LocalBypassAdvancedRows(
                uiState = uiState,
                desyncSummary = desyncSummary,
                onOpenDesyncSettings = onOpenDesyncSettings,
                onOpenDnsSettings = onOpenDnsSettings,
            )
        }
    }
}

@Composable
private fun LocalBypassSimpleCard(
    uiState: ConfigUiState,
    onRequestVpnPermission: () -> Unit,
    onDismissRuntimeError: () -> Unit,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit,
    onRetestStrategies: () -> Unit,
) {
    val localBypassEnabled = uiState.runningMode == Mode.Proxy

    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassSimple)) {
        RipDpiPanelHeader(title = stringResource(R.string.config_local_bypass_simple_title))
        Text(
            text = stringResource(R.string.config_local_bypass_simple_body),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        LocalBypassToggleStateRow(localBypassEnabled = localBypassEnabled)
        LocalBypassStrategyRow()
        LocalBypassPrecedenceRow(commandLineOverridesEnabled = uiState.draft.useCommandLineSettings)
        LocalBypassActionButtons(
            uiState = uiState,
            onRequestVpnPermission = onRequestVpnPermission,
            onDismissRuntimeError = onDismissRuntimeError,
            localBypassEnabled = localBypassEnabled,
            onRuntimeModeToggle = onRuntimeModeToggle,
            onRetestStrategies = onRetestStrategies,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LocalBypassToggleStateRow(localBypassEnabled: Boolean) {
    SettingsRow(
        title = stringResource(R.string.config_local_bypass_toggle_title),
        subtitle = stringResource(R.string.config_local_bypass_toggle_body),
        value =
            stringResource(
                if (localBypassEnabled) {
                    R.string.config_local_bypass_enabled
                } else {
                    R.string.config_local_bypass_disabled
                },
            ),
        leadingIcon = RipDpiIcons.NetworkCheck,
        showDivider = true,
        testTag = RipDpiTestTags.ConfigLocalBypassToggleState,
    )
}

@Composable
internal fun LocalBypassStrategyRow() {
    SettingsRow(
        title = stringResource(R.string.config_local_bypass_strategy_title),
        subtitle = stringResource(R.string.config_local_bypass_strategy_body),
        value = stringResource(R.string.config_local_bypass_strategy_auto),
        leadingIcon = RipDpiIcons.Shield,
        showDivider = true,
        testTag = RipDpiTestTags.ConfigLocalBypassStrategyAuto,
    )
}

@Composable
internal fun LocalBypassPrecedenceRow(commandLineOverridesEnabled: Boolean) {
    SettingsRow(
        title = stringResource(R.string.config_local_bypass_precedence_title),
        subtitle = stringResource(R.string.config_local_bypass_precedence_body),
        value =
            stringResource(
                if (commandLineOverridesEnabled) {
                    R.string.config_local_bypass_precedence_cli_value
                } else {
                    R.string.config_local_bypass_precedence_visual_value
                },
            ),
        leadingIcon = RipDpiIcons.Info,
        showDivider = false,
        testTag = RipDpiTestTags.ConfigLocalBypassPrecedenceNote,
    )
}

@Composable
private fun LocalBypassActionButtons(
    uiState: ConfigUiState,
    onRequestVpnPermission: () -> Unit,
    onDismissRuntimeError: () -> Unit,
    localBypassEnabled: Boolean,
    onRuntimeModeToggle: (Mode, Boolean) -> Unit,
    onRetestStrategies: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RipDpiButton(
            text =
                stringResource(
                    uiState.runtimeOperation?.labelRes()
                        ?: if (localBypassEnabled) {
                            R.string.config_local_bypass_turn_off
                        } else {
                            R.string.config_local_bypass_turn_on
                        },
                ),
            onClick = {
                when (localBypassToggleAction(localBypassEnabled)) {
                    LocalBypassToggleAction.TurnOn -> onRuntimeModeToggle(Mode.Proxy, true)
                    LocalBypassToggleAction.TurnOff -> onRuntimeModeToggle(Mode.Proxy, false)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassToggle),
            variant = RipDpiButtonVariant.Primary,
            leadingIcon = RipDpiIcons.NetworkCheck,
            enabled = uiState.runtimeOperation == null,
            loading = uiState.runtimeOperation != null,
        )
        ConfigRuntimeFeedback(uiState, onRequestVpnPermission, onDismissRuntimeError)
        RipDpiButton(
            text = stringResource(R.string.config_local_bypass_retest_strategies),
            onClick = onRetestStrategies,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ConfigLocalBypassRetest),
            variant = RipDpiButtonVariant.Outline,
            leadingIcon = RipDpiIcons.Refresh,
        )
    }
}

internal enum class LocalBypassToggleAction {
    TurnOn,
    TurnOff,
}

internal fun localBypassToggleAction(localBypassEnabled: Boolean): LocalBypassToggleAction =
    if (localBypassEnabled) {
        LocalBypassToggleAction.TurnOff
    } else {
        LocalBypassToggleAction.TurnOn
    }

@Composable
private fun LocalBypassAdvancedRows(
    uiState: ConfigUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    showGuidance: Boolean = true,
) {
    RipDpiCard {
        if (showGuidance) {
            Text(
                text = stringResource(R.string.config_local_bypass_section_body),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        LocalBypassModeRow(uiState = uiState)
        // In VPN mode the listener is internal to the tunnel; only proxy-mode users point apps at it.
        if (uiState.draft.mode == Mode.Proxy) LocalBypassListenAddressRow(uiState = uiState)
        LocalBypassDnsRow(uiState = uiState, onOpenDnsSettings = onOpenDnsSettings)
        LocalBypassDesyncRow(
            uiState = uiState,
            desyncSummary = desyncSummary,
            onOpenDesyncSettings = onOpenDesyncSettings,
        )
    }
}

@Composable
private fun LocalBypassModeRow(uiState: ConfigUiState) {
    SettingsRow(
        title = stringResource(R.string.mode_setting),
        subtitle = stringResource(R.string.config_mode_summary),
        value = stringResource(modeLabelRes(uiState.draft.mode)),
        // Read-only rows carry no icon badge, so they do not look like the navigable rows below.
        showDivider = true,
        testTag = RipDpiTestTags.ConfigLocalBypassMode,
    )
}

@Composable
private fun LocalBypassListenAddressRow(uiState: ConfigUiState) {
    EndpointSettingsRow(
        title = stringResource(R.string.bye_dpi_proxy_ip_setting),
        subtitle = stringResource(R.string.config_listen_address_summary),
        value =
            stringResource(
                R.string.proxy_address,
                uiState.draft.proxyIp,
                uiState.draft.proxyPort,
            ),
        monospaceValue = true,
        leadingIcon = null,
        testTag = RipDpiTestTags.ConfigLocalBypassListenAddress,
    )
}

@Composable
private fun LocalBypassDnsRow(
    uiState: ConfigUiState,
    onOpenDnsSettings: () -> Unit,
) {
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigDnsSettings,
        accessibilityLabel = stringResource(R.string.title_dns_settings),
        accessibilityValue = uiState.draft.dnsSummary,
        onClick = onOpenDnsSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.title_dns_settings),
            subtitle =
                stringResource(
                    if (uiState.draft.mode == Mode.VPN) {
                        R.string.config_dns_summary_enabled
                    } else {
                        R.string.config_dns_summary_disabled
                    },
                ),
            value = uiState.draft.dnsSummary,
            leadingIcon = RipDpiIcons.Dns,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun LocalBypassDesyncRow(
    uiState: ConfigUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
) {
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigLocalBypassDesync,
        accessibilityLabel = stringResource(R.string.ripdpi_desync_method_setting),
        accessibilityValue = desyncSummary,
        onClick = onOpenDesyncSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_desync_method_setting),
            value = desyncSummary,
            leadingIcon = RipDpiIcons.Shield,
            showChevron = true,
            showDivider = uiState.draft.defaultTtl.isNotBlank(),
        )
    }
    if (uiState.draft.defaultTtl.isNotBlank()) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_default_ttl_setting),
            subtitle = stringResource(R.string.config_default_ttl_summary),
            value = uiState.draft.defaultTtl,
            leadingIcon = RipDpiIcons.Timer,
            monospaceValue = true,
        )
    }
}

@Composable
private fun LocalBypassActionRow(
    testTag: String,
    accessibilityLabel: String,
    accessibilityValue: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    this.testTag = testTag
                    contentDescription = accessibilityLabel
                    stateDescription = accessibilityValue
                    role = Role.Button
                    onClick(action = {
                        onClick()
                        true
                    })
                },
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun LocalBypassConfigScreenPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            proxyIp = "127.0.0.1",
            proxyPort = "1080",
            defaultTtl = "8",
        )
    RipDpiTheme {
        LocalBypassConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    runningMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            desyncSummary = draft.chainSummary,
            onRuntimeModeToggle = { _, _ -> },
            onOpenDesyncSettings = {},
            onOpenDnsSettings = {},
            onRetestStrategies = {},
        )
    }
}

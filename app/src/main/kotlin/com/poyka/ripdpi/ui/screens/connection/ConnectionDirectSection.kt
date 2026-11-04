package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.cards.EndpointSettingsRow
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.screens.dns.localizedDnsSummary
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPersona
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ConnectionDirectSection(
    uiState: ConnectionUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRetestStrategies: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("ConnectionDirectSection")

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        DirectSectionRows(uiState, desyncSummary, onOpenDesyncSettings, onOpenDnsSettings, onRetestStrategies)
    }
}

@Composable
private fun DirectSectionRows(
    uiState: ConnectionUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRetestStrategies: () -> Unit,
) {
    RipDpiCard {
        // The mode chips above the tabs show the mode; a row here would repeat it.
        // In VPN mode the listener is internal to the tunnel; only proxy-mode users point apps at it.
        if (uiState.draft.mode == Mode.Proxy) LocalBypassListenAddressRow(uiState = uiState)
        LocalBypassDnsRow(uiState = uiState, onOpenDnsSettings = onOpenDnsSettings)
        LocalBypassDesyncRow(
            uiState = uiState,
            desyncSummary = desyncSummary,
            onOpenDesyncSettings = onOpenDesyncSettings,
        )
        LocalBypassStrategyTestRow(onRetestStrategies)
    }
}

@Composable
private fun LocalBypassStrategyTestRow(onRetestStrategies: () -> Unit) {
    val title = stringResource(R.string.config_run_strategy_test)
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigLocalBypassRetest,
        accessibilityLabel = title,
        accessibilityValue = "",
        onClick = onRetestStrategies,
    ) {
        SettingsRow(title = title, leadingIcon = RipDpiIcons.Refresh, showChevron = true)
    }
}

@Composable
private fun LocalBypassListenAddressRow(uiState: ConnectionUiState) {
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
    uiState: ConnectionUiState,
    onOpenDnsSettings: () -> Unit,
) {
    val dnsSummary = uiState.draft.localizedDnsSummary()
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigDnsSettings,
        accessibilityLabel = stringResource(R.string.title_dns_settings),
        accessibilityValue = dnsSummary,
        onClick = onOpenDnsSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.title_dns_settings),
            value = dnsSummary,
            leadingIcon = RipDpiIcons.Dns,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun LocalBypassDesyncRow(
    uiState: ConnectionUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
) {
    // Guided users see Auto only while the chain is untouched; any manual chain stays visible as written.
    val isAuto =
        OnboardingPersona.fromId(uiState.uiPersona) == OnboardingPersona.Guided &&
            desyncSummary == AppSettingsSerializer.defaultValue.toConfigDraft().chainSummary
    val strategyValue = if (isAuto) stringResource(R.string.config_local_bypass_strategy_auto) else desyncSummary
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigLocalBypassDesync,
        accessibilityLabel = stringResource(R.string.ripdpi_desync_method_setting),
        accessibilityValue = strategyValue,
        onClick = onOpenDesyncSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_desync_method_setting),
            value = strategyValue,
            leadingIcon = RipDpiIcons.Shield,
            monospaceValue = !isAuto,
            showChevron = true,
            showDivider = true,
        )
    }
    if (uiState.draft.defaultTtl.isNotBlank()) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_default_ttl_setting),
            subtitle = stringResource(R.string.config_default_ttl_summary),
            value = uiState.draft.defaultTtl,
            leadingIcon = RipDpiIcons.Timer,
            monospaceValue = true,
            showDivider = true,
        )
    }
}

@Composable
private fun LocalBypassActionRow(
    testTag: String,
    accessibilityLabel: String,
    accessibilityValue: String,
    onClick: () -> Unit,
    accessibilitySubtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    this.testTag = testTag
                    // The cleared subtree hides the row subtitle, so it joins the label here.
                    contentDescription = listOfNotNull(accessibilityLabel, accessibilitySubtitle).joinToString(", ")
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
        ConnectionDirectSection(
            uiState =
                ConnectionUiState(
                    activeMode = draft.mode,
                    runningMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            desyncSummary = draft.chainSummary,
            onOpenDesyncSettings = {},
            onOpenDnsSettings = {},
            onRetestStrategies = {},
        )
    }
}

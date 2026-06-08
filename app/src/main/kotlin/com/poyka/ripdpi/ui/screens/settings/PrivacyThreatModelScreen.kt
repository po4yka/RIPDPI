package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DeviceEgressUiState
import com.poyka.ripdpi.activities.HardKillSwitchUiState
import com.poyka.ripdpi.activities.PrivacyThreatIndicatorUiState
import com.poyka.ripdpi.activities.PrivacyThreatTone
import com.poyka.ripdpi.activities.PrivacyThreatUiState
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.services.PrivacyLeakIndicatorStatus
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun PrivacyThreatModelRoute(
    uiState: PrivacyThreatUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PrivacyThreatModelScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun PrivacyThreatModelScreen(
    uiState: PrivacyThreatUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.PrivacyThreatModel))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_privacy_threat_model),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            PrivacyThreatSection(title = stringResource(R.string.privacy_threat_hard_kill_switch_section)) {
                KillSwitchStatusCard(uiState = uiState.killSwitch)
            }
        }
        item {
            PrivacyThreatSection(title = stringResource(R.string.privacy_threat_leak_indicators_section)) {
                LeakIndicatorCard(
                    indicator = uiState.dnsLeak,
                    testTag = RipDpiTestTags.PrivacyThreatDnsLeak,
                )
                LeakIndicatorCard(
                    indicator = uiState.ipv6Leak,
                    testTag = RipDpiTestTags.PrivacyThreatIpv6Leak,
                )
            }
        }
        item {
            PrivacyThreatSection(title = stringResource(R.string.privacy_threat_egress_section)) {
                DeviceEgressCard(uiState = uiState.deviceEgress)
            }
        }
    }
}

@Composable
private fun PrivacyThreatSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = title)
        content()
    }
}

@Composable
private fun KillSwitchStatusCard(uiState: HardKillSwitchUiState) {
    StatusCard(
        title = uiState.label,
        statusLabel =
            when (uiState.status) {
                AndroidHardKillSwitchStatus.ENABLED -> stringResource(R.string.privacy_threat_status_enabled)
                AndroidHardKillSwitchStatus.NOT_ENABLED -> stringResource(R.string.privacy_threat_status_action_needed)
                AndroidHardKillSwitchStatus.UNKNOWN -> stringResource(R.string.privacy_threat_status_unknown)
            },
        summary = uiState.summary,
        tone =
            when (uiState.status) {
                AndroidHardKillSwitchStatus.ENABLED -> PrivacyThreatTone.SECURE
                AndroidHardKillSwitchStatus.NOT_ENABLED -> PrivacyThreatTone.WARNING
                AndroidHardKillSwitchStatus.UNKNOWN -> PrivacyThreatTone.UNKNOWN
            },
        testTag = RipDpiTestTags.PrivacyThreatKillSwitch,
    )
}

@Composable
private fun LeakIndicatorCard(
    indicator: PrivacyThreatIndicatorUiState,
    testTag: String,
) {
    StatusCard(
        title = indicator.title,
        statusLabel = indicator.statusLabel,
        summary = indicator.summary,
        tone = indicator.tone,
        testTag = testTag,
    )
}

@Composable
private fun DeviceEgressCard(uiState: DeviceEgressUiState) {
    StatusCard(
        title = uiState.title,
        statusLabel = uiState.statusLabel,
        summary = uiState.summary,
        tone = uiState.tone,
        testTag = RipDpiTestTags.PrivacyThreatDeviceEgress,
    )
}

@Composable
private fun StatusCard(
    title: String,
    statusLabel: String,
    summary: String,
    tone: PrivacyThreatTone,
    testTag: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val statusColor =
        when (tone) {
            PrivacyThreatTone.SECURE -> colors.success
            PrivacyThreatTone.WARNING -> colors.warning
            PrivacyThreatTone.CRITICAL -> colors.destructive
            PrivacyThreatTone.UNKNOWN -> colors.mutedForeground
        }

    RipDpiCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(testTag),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = title,
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = statusLabel,
                style = type.smallLabel,
                color = statusColor,
            )
            Text(
                text = summary,
                style = type.body,
                color = colors.mutedForeground,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun previewPrivacyThreatModelScreen() {
    RipDpiTheme {
        PrivacyThreatModelScreen(
            uiState =
                PrivacyThreatUiState(
                    killSwitch =
                        HardKillSwitchUiState(
                            status = AndroidHardKillSwitchStatus.ENABLED,
                            label = "System lockdown enabled",
                            summary = "Android is blocking traffic outside the VPN.",
                        ),
                    dnsLeak =
                        PrivacyThreatIndicatorUiState(
                            title = "DNS leak",
                            status = PrivacyLeakIndicatorStatus.CLEAR,
                            statusLabel = "Clear",
                            summary = "DNS checks stay inside the protected path.",
                            tone = PrivacyThreatTone.SECURE,
                        ),
                    ipv6Leak =
                        PrivacyThreatIndicatorUiState(
                            title = "IPv6 leak",
                            status = PrivacyLeakIndicatorStatus.NOT_CHECKED,
                            statusLabel = "Not checked",
                            summary = "No IPv6 leak check has run in this session.",
                        ),
                    deviceEgress =
                        DeviceEgressUiState(
                            title = "What is leaving the device",
                            statusLabel = "VPN tunnel + lockdown",
                            summary = "App traffic is expected to leave through the Android VPN.",
                            tone = PrivacyThreatTone.SECURE,
                        ),
                ),
            onBack = {},
        )
    }
}

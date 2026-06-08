package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import com.poyka.ripdpi.services.PrivacyLeakIndicatorSnapshot
import com.poyka.ripdpi.services.PrivacyLeakIndicatorStatus
import com.poyka.ripdpi.services.PrivacyThreatSnapshot

enum class PrivacyThreatTone {
    SECURE,
    WARNING,
    CRITICAL,
    UNKNOWN,
}

@Immutable
data class PrivacyThreatIndicatorUiState(
    val title: String = "",
    val status: PrivacyLeakIndicatorStatus = PrivacyLeakIndicatorStatus.UNKNOWN,
    val statusLabel: String = "",
    val summary: String = "",
    val tone: PrivacyThreatTone = PrivacyThreatTone.UNKNOWN,
)

@Immutable
data class DeviceEgressUiState(
    val title: String = "",
    val statusLabel: String = "",
    val summary: String = "",
    val tone: PrivacyThreatTone = PrivacyThreatTone.UNKNOWN,
)

@Immutable
data class PrivacyThreatUiState(
    val killSwitch: HardKillSwitchUiState = HardKillSwitchUiState(),
    val dnsLeak: PrivacyThreatIndicatorUiState = PrivacyThreatIndicatorUiState(),
    val ipv6Leak: PrivacyThreatIndicatorUiState = PrivacyThreatIndicatorUiState(),
    val deviceEgress: DeviceEgressUiState = DeviceEgressUiState(),
)

internal fun buildPrivacyThreatUiState(
    snapshot: PrivacyThreatSnapshot,
    hardKillSwitch: HardKillSwitchUiState,
    activeMode: Mode,
    configuredMode: Mode,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    stringResolver: StringResolver,
): PrivacyThreatUiState =
    PrivacyThreatUiState(
        killSwitch = hardKillSwitch,
        dnsLeak =
            buildLeakIndicatorUiState(
                title = stringResolver.getString(R.string.privacy_threat_dns_leak_title),
                indicator = snapshot.dnsLeak,
                clearSummary = stringResolver.getString(R.string.privacy_threat_dns_leak_clear),
                notCheckedSummary = stringResolver.getString(R.string.privacy_threat_dns_leak_not_checked),
                leakedSummary = R.string.privacy_threat_dns_leak_detected,
                unknownSummary = stringResolver.getString(R.string.privacy_threat_leak_unknown),
                stringResolver = stringResolver,
            ),
        ipv6Leak =
            buildLeakIndicatorUiState(
                title = stringResolver.getString(R.string.privacy_threat_ipv6_leak_title),
                indicator = snapshot.ipv6Leak,
                clearSummary = stringResolver.getString(R.string.privacy_threat_ipv6_leak_clear),
                notCheckedSummary = stringResolver.getString(R.string.privacy_threat_ipv6_leak_not_checked),
                leakedSummary = R.string.privacy_threat_ipv6_leak_detected,
                unknownSummary = stringResolver.getString(R.string.privacy_threat_leak_unknown),
                stringResolver = stringResolver,
            ),
        deviceEgress =
            buildDeviceEgressUiState(
                hardKillSwitch = hardKillSwitch,
                activeMode = activeMode,
                configuredMode = configuredMode,
                appStatus = appStatus,
                connectionState = connectionState,
                stringResolver = stringResolver,
            ),
    )

private fun buildLeakIndicatorUiState(
    title: String,
    indicator: PrivacyLeakIndicatorSnapshot,
    clearSummary: String,
    notCheckedSummary: String,
    leakedSummary: Int,
    unknownSummary: String,
    stringResolver: StringResolver,
): PrivacyThreatIndicatorUiState =
    when (indicator.status) {
        PrivacyLeakIndicatorStatus.CLEAR -> {
            PrivacyThreatIndicatorUiState(
                title = title,
                status = indicator.status,
                statusLabel = stringResolver.getString(R.string.privacy_threat_status_clear),
                summary = clearSummary,
                tone = PrivacyThreatTone.SECURE,
            )
        }

        PrivacyLeakIndicatorStatus.LEAK_DETECTED -> {
            PrivacyThreatIndicatorUiState(
                title = title,
                status = indicator.status,
                statusLabel = stringResolver.getString(R.string.privacy_threat_status_leak_detected),
                summary =
                    indicator.detail
                        ?.let { detail -> stringResolver.getString(leakedSummary, detail) }
                        ?: stringResolver.getString(leakedSummary, ""),
                tone = PrivacyThreatTone.CRITICAL,
            )
        }

        PrivacyLeakIndicatorStatus.NOT_CHECKED -> {
            PrivacyThreatIndicatorUiState(
                title = title,
                status = indicator.status,
                statusLabel = stringResolver.getString(R.string.privacy_threat_status_not_checked),
                summary = notCheckedSummary,
                tone = PrivacyThreatTone.UNKNOWN,
            )
        }

        PrivacyLeakIndicatorStatus.UNKNOWN -> {
            PrivacyThreatIndicatorUiState(
                title = title,
                status = indicator.status,
                statusLabel = stringResolver.getString(R.string.privacy_threat_status_unknown),
                summary = unknownSummary,
                tone = PrivacyThreatTone.UNKNOWN,
            )
        }
    }

private fun buildDeviceEgressUiState(
    hardKillSwitch: HardKillSwitchUiState,
    activeMode: Mode,
    configuredMode: Mode,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    stringResolver: StringResolver,
): DeviceEgressUiState {
    val mode = if (appStatus == AppStatus.Running) activeMode else configuredMode
    return when {
        connectionState == ConnectionState.Connected &&
            mode == Mode.VPN &&
            hardKillSwitch.status == AndroidHardKillSwitchStatus.ENABLED -> {
            DeviceEgressUiState(
                title = stringResolver.getString(R.string.privacy_threat_egress_title),
                statusLabel = stringResolver.getString(R.string.privacy_threat_egress_vpn_lockdown_label),
                summary = stringResolver.getString(R.string.privacy_threat_egress_vpn_lockdown_summary),
                tone = PrivacyThreatTone.SECURE,
            )
        }

        connectionState == ConnectionState.Connected && mode == Mode.VPN -> {
            DeviceEgressUiState(
                title = stringResolver.getString(R.string.privacy_threat_egress_title),
                statusLabel = stringResolver.getString(R.string.privacy_threat_egress_vpn_label),
                summary = stringResolver.getString(R.string.privacy_threat_egress_vpn_summary),
                tone = PrivacyThreatTone.WARNING,
            )
        }

        connectionState == ConnectionState.Connected && mode == Mode.Proxy -> {
            DeviceEgressUiState(
                title = stringResolver.getString(R.string.privacy_threat_egress_title),
                statusLabel = stringResolver.getString(R.string.privacy_threat_egress_proxy_label),
                summary = stringResolver.getString(R.string.privacy_threat_egress_proxy_summary),
                tone = PrivacyThreatTone.WARNING,
            )
        }

        connectionState == ConnectionState.Connecting -> {
            DeviceEgressUiState(
                title = stringResolver.getString(R.string.privacy_threat_egress_title),
                statusLabel = stringResolver.getString(R.string.privacy_threat_egress_connecting_label),
                summary = stringResolver.getString(R.string.privacy_threat_egress_connecting_summary),
                tone = PrivacyThreatTone.UNKNOWN,
            )
        }

        else -> {
            DeviceEgressUiState(
                title = stringResolver.getString(R.string.privacy_threat_egress_title),
                statusLabel = stringResolver.getString(R.string.privacy_threat_egress_inactive_label),
                summary = stringResolver.getString(R.string.privacy_threat_egress_inactive_summary),
                tone = PrivacyThreatTone.UNKNOWN,
            )
        }
    }
}

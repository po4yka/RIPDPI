package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.BatteryOptimizationGuidance
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun buildPermissionSummary(
    snapshot: PermissionSnapshot,
    issue: PermissionIssueUiState?,
    configuredMode: Mode,
    stringResolver: StringResolver,
    deviceManufacturer: String,
    batteryBannerDismissed: Boolean = false,
    backgroundGuidanceDismissed: Boolean = false,
): PermissionSummaryUiState {
    val batteryOptimizationRecommended =
        issue == null &&
            !batteryBannerDismissed &&
            snapshot.batteryOptimization != PermissionStatus.Granted &&
            snapshot.batteryOptimization != PermissionStatus.NotApplicable
    val recommendedIssue =
        if (batteryOptimizationRecommended) {
            createPermissionIssue(
                kind = PermissionKind.BatteryOptimization,
                status = snapshot.batteryOptimization,
                blocking = false,
                stringResolver = stringResolver,
            )
        } else {
            null
        }

    return PermissionSummaryUiState(
        snapshot = snapshot,
        issue = issue,
        recommendedIssue = recommendedIssue,
        backgroundGuidance =
            if (backgroundGuidanceDismissed) {
                null
            } else {
                buildBackgroundGuidance(stringResolver, deviceManufacturer)
            },
        items =
            listOf(
                buildNotificationPermissionItem(snapshot.notifications, stringResolver),
                buildVpnPermissionItem(snapshot.vpnConsent, configuredMode, stringResolver),
                buildBatteryPermissionItem(snapshot.batteryOptimization, stringResolver),
            ).toImmutableList(),
    )
}

internal fun buildNotificationPermissionItem(
    status: PermissionStatus,
    stringResolver: StringResolver,
): PermissionItemUiState =
    when (status) {
        PermissionStatus.Granted,
        PermissionStatus.NotApplicable,
        -> {
            PermissionItemUiState(
                kind = PermissionKind.Notifications,
                title = stringResolver.getString(R.string.permissions_notifications_title),
                subtitle = stringResolver.getString(R.string.settings_permissions_notifications_ready),
                statusLabel = stringResolver.getString(R.string.settings_permission_status_granted),
            )
        }

        PermissionStatus.RequiresSettings -> {
            PermissionItemUiState(
                kind = PermissionKind.Notifications,
                title = stringResolver.getString(R.string.permissions_notifications_title),
                subtitle = stringResolver.getString(R.string.settings_permissions_notifications_needed),
                statusLabel = stringResolver.getString(R.string.settings_permission_status_required),
                actionLabel = stringResolver.getString(R.string.settings_permission_action_open_settings),
            )
        }

        PermissionStatus.Denied,
        PermissionStatus.RequiresSystemPrompt,
        -> {
            PermissionItemUiState(
                kind = PermissionKind.Notifications,
                title = stringResolver.getString(R.string.permissions_notifications_title),
                subtitle = stringResolver.getString(R.string.settings_permissions_notifications_needed),
                statusLabel = stringResolver.getString(R.string.settings_permission_status_required),
                actionLabel = stringResolver.getString(R.string.settings_permission_action_allow),
            )
        }
    }

internal fun buildVpnPermissionItem(
    status: PermissionStatus,
    configuredMode: Mode,
    stringResolver: StringResolver,
): PermissionItemUiState =
    when (status) {
        PermissionStatus.Granted -> {
            PermissionItemUiState(
                kind = PermissionKind.VpnConsent,
                title = stringResolver.getString(R.string.permissions_vpn_title),
                subtitle =
                    if (configuredMode == Mode.VPN) {
                        stringResolver.getString(R.string.settings_permissions_vpn_active)
                    } else {
                        stringResolver.getString(R.string.settings_permissions_vpn_optional)
                    },
                statusLabel = stringResolver.getString(R.string.settings_permission_status_granted),
            )
        }

        PermissionStatus.NotApplicable -> {
            PermissionItemUiState(
                kind = PermissionKind.VpnConsent,
                title = stringResolver.getString(R.string.permissions_vpn_title),
                subtitle = stringResolver.getString(R.string.settings_permissions_vpn_optional),
                statusLabel = stringResolver.getString(R.string.settings_permission_status_not_needed),
            )
        }

        PermissionStatus.Denied,
        PermissionStatus.RequiresSettings,
        PermissionStatus.RequiresSystemPrompt,
        -> {
            PermissionItemUiState(
                kind = PermissionKind.VpnConsent,
                title = stringResolver.getString(R.string.permissions_vpn_title),
                subtitle =
                    if (configuredMode == Mode.VPN) {
                        stringResolver.getString(R.string.settings_permissions_vpn_needed)
                    } else {
                        stringResolver.getString(R.string.settings_permissions_vpn_optional)
                    },
                statusLabel =
                    if (configuredMode == Mode.VPN) {
                        stringResolver.getString(R.string.settings_permission_status_required)
                    } else {
                        stringResolver.getString(R.string.settings_permission_status_optional)
                    },
                actionLabel = stringResolver.getString(R.string.permissions_vpn_continue),
            )
        }
    }

internal fun buildBatteryPermissionItem(
    status: PermissionStatus,
    stringResolver: StringResolver,
): PermissionItemUiState =
    when (status) {
        PermissionStatus.Granted,
        PermissionStatus.NotApplicable,
        -> {
            PermissionItemUiState(
                kind = PermissionKind.BatteryOptimization,
                title = stringResolver.getString(R.string.permissions_battery_title),
                subtitle = stringResolver.getString(BatteryOptimizationGuidance.dozeReadySubtitleRes()),
                statusLabel =
                    if (status == PermissionStatus.NotApplicable) {
                        stringResolver.getString(R.string.settings_permission_status_not_needed)
                    } else {
                        stringResolver.getString(R.string.settings_permission_status_granted)
                    },
            )
        }

        PermissionStatus.Denied,
        PermissionStatus.RequiresSystemPrompt,
        PermissionStatus.RequiresSettings,
        -> {
            PermissionItemUiState(
                kind = PermissionKind.BatteryOptimization,
                title = stringResolver.getString(R.string.permissions_battery_title),
                subtitle = stringResolver.getString(BatteryOptimizationGuidance.dozeRecommendedSubtitleRes()),
                statusLabel = stringResolver.getString(R.string.settings_permission_status_recommended),
                actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
            )
        }
    }

internal fun buildBackgroundGuidance(
    stringResolver: StringResolver,
    deviceManufacturer: String,
): BackgroundGuidanceUiState =
    BackgroundGuidanceUiState(
        title = stringResolver.getString(BatteryOptimizationGuidance.backgroundGuidanceTitleRes()),
        message =
            stringResolver.getString(
                BatteryOptimizationGuidance.backgroundGuidanceMessageRes(deviceManufacturer),
            ),
    )

internal fun createPermissionIssue(
    kind: PermissionKind,
    status: PermissionStatus,
    blocking: Boolean,
    stringResolver: StringResolver,
): PermissionIssueUiState =
    when (kind) {
        PermissionKind.Notifications -> {
            if (status == PermissionStatus.RequiresSettings) {
                PermissionIssueUiState(
                    kind = kind,
                    title = stringResolver.getString(R.string.permissions_notifications_title),
                    message = stringResolver.getString(R.string.permissions_notifications_open_settings),
                    recovery = PermissionRecovery.OpenSettings,
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_open_settings),
                    blocking = blocking,
                )
            } else {
                PermissionIssueUiState(
                    kind = kind,
                    title = stringResolver.getString(R.string.permissions_notifications_title),
                    message = stringResolver.getString(R.string.permissions_notifications_denied),
                    recovery = PermissionRecovery.RetryPrompt,
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_allow),
                    blocking = blocking,
                )
            }
        }

        PermissionKind.VpnConsent -> {
            PermissionIssueUiState(
                kind = kind,
                title = stringResolver.getString(R.string.permissions_vpn_error_title),
                message = stringResolver.getString(R.string.permissions_vpn_error_body),
                recovery = PermissionRecovery.ShowVpnPermissionDialog,
                actionLabel = stringResolver.getString(R.string.permissions_vpn_continue),
                blocking = blocking,
            )
        }

        PermissionKind.BatteryOptimization -> {
            PermissionIssueUiState(
                kind = kind,
                title = stringResolver.getString(R.string.permissions_battery_title),
                message = stringResolver.getString(BatteryOptimizationGuidance.dozeIssueMessageRes()),
                recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
                blocking = blocking,
            )
        }
    }

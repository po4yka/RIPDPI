@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.BatteryOptimizationGuidance
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class MainPermissionActions(
    private val mutations: MainMutationRunner,
    private val permissionCoordinator: PermissionCoordinator,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val permissionPlatformBridge: PermissionPlatformBridge,
    private val stringResolver: StringResolver,
    private val deviceManufacturer: String,
    val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val onStartMode: (Mode) -> Unit,
    private val onShowPermissionIssue: (PermissionIssueUiState) -> Unit,
    private val onDismissError: () -> Unit,
) {
    private val permissionOverrides = mutableMapOf<PermissionKind, PermissionStatus>()
    private var pendingPermissionAction: PermissionAction? = null

    fun onVpnPermissionContinueRequested() {
        resolvePermissionAction(PermissionAction.StartVpnMode)
    }

    fun onOpenVpnPermissionRequested() {
        permissionState.update { current -> current.copy(issue = null) }
        mutations.trySend(MainEffect.ShowVpnPermissionDialog)
    }

    fun onRepairPermissionRequested(kind: PermissionKind) {
        resolvePermissionAction(PermissionAction.RepairPermission(kind))
    }

    fun onPermissionResult(
        kind: PermissionKind,
        result: PermissionResult,
    ) {
        when (kind) {
            PermissionKind.Notifications -> handleNotificationPermissionResult(result)
            PermissionKind.VpnConsent -> handleVpnPermissionResult(result)
            PermissionKind.BatteryOptimization -> handleBatteryOptimizationResult()
        }
    }

    fun refreshPermissionSnapshot() {
        val mergedSnapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
        permissionState.update { current ->
            val clearedIssue =
                if (current.issue?.kind?.let { mergedSnapshot.statusFor(it) == PermissionStatus.Granted } == true) {
                    null
                } else {
                    current.issue
                }
            current.copy(snapshot = mergedSnapshot, issue = clearedIssue)
        }
    }

    fun resolvePermissionAction(action: PermissionAction) {
        if (
            (action is PermissionAction.StartConfiguredMode || action is PermissionAction.StartVpnMode) &&
            mutations.currentUiState().connectionState == ConnectionState.Connecting
        ) {
            return
        }

        onDismissError()
        val mergedSnapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
        permissionState.update { it.copy(snapshot = mergedSnapshot) }
        val resolution =
            permissionCoordinator.resolve(
                action = action,
                configuredMode = mutations.currentUiState().configuredMode,
                snapshot = mergedSnapshot,
            )
        pendingPermissionAction = action
        val blockedBy = resolution.blockedBy
        if (blockedBy == null) {
            permissionState.update { it.copy(issue = null) }
            continueResolvedAction(action, resolution.recommended)
            return
        }

        requestPermissionFor(action = action, blockedBy = blockedBy, snapshot = mergedSnapshot)
    }

    fun buildPermissionSummary(
        snapshot: PermissionSnapshot,
        issue: PermissionIssueUiState?,
        configuredMode: Mode,
    ): PermissionSummaryUiState {
        val recommendedIssue =
            if (
                issue == null &&
                snapshot.batteryOptimization != PermissionStatus.Granted &&
                snapshot.batteryOptimization != PermissionStatus.NotApplicable
            ) {
                createPermissionIssue(
                    kind = PermissionKind.BatteryOptimization,
                    status = snapshot.batteryOptimization,
                    blocking = false,
                )
            } else {
                null
            }

        return PermissionSummaryUiState(
            snapshot = snapshot,
            issue = issue,
            recommendedIssue = recommendedIssue,
            items =
                listOf(
                    buildNotificationPermissionItem(snapshot.notifications),
                    buildVpnPermissionItem(snapshot.vpnConsent, configuredMode),
                    buildBatteryPermissionItem(snapshot.batteryOptimization),
                ),
        )
    }

    private fun requestPermissionFor(
        action: PermissionAction,
        blockedBy: PermissionKind,
        snapshot: PermissionSnapshot,
    ) {
        when (blockedBy) {
            PermissionKind.Notifications -> {
                when (snapshot.notifications) {
                    PermissionStatus.RequiresSettings -> {
                        val issue =
                            createPermissionIssue(
                                kind = PermissionKind.Notifications,
                                status = PermissionStatus.RequiresSettings,
                                blocking = true,
                            )
                        permissionState.update { it.copy(issue = issue, snapshot = snapshot) }
                        mutations.trySend(MainEffect.OpenAppSettings(createAppSettingsIntent()))
                    }

                    PermissionStatus.Denied,
                    PermissionStatus.RequiresSystemPrompt,
                    -> {
                        permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                        mutations.trySend(MainEffect.RequestPermission(kind = PermissionKind.Notifications))
                    }

                    PermissionStatus.Granted,
                    PermissionStatus.NotApplicable,
                    -> {
                        continueResolvedAction(action, emptyList())
                    }
                }
            }

            PermissionKind.VpnConsent -> {
                when (action) {
                    PermissionAction.StartConfiguredMode -> {
                        mutations.trySend(MainEffect.ShowVpnPermissionDialog)
                    }

                    PermissionAction.StartVpnMode,
                    is PermissionAction.RepairPermission,
                    -> {
                        val prepareIntent = permissionPlatformBridge.prepareVpnPermissionIntent()
                        if (prepareIntent == null) {
                            onPermissionResult(PermissionKind.VpnConsent, PermissionResult.Granted)
                        } else {
                            permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                            mutations.trySend(
                                MainEffect.RequestPermission(
                                    kind = PermissionKind.VpnConsent,
                                    payload = prepareIntent,
                                ),
                            )
                        }
                    }
                }
            }

            PermissionKind.BatteryOptimization -> {
                permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                mutations.trySend(
                    MainEffect.RequestPermission(
                        kind = PermissionKind.BatteryOptimization,
                        payload = createBatteryOptimizationIntent(),
                    ),
                )
            }
        }
    }

    private fun handleNotificationPermissionResult(result: PermissionResult) {
        when (result) {
            PermissionResult.Granted -> {
                permissionOverrides.remove(PermissionKind.Notifications)
                refreshPermissionSnapshot()
                resumePendingAction()
            }

            PermissionResult.Denied -> {
                permissionOverrides[PermissionKind.Notifications] = PermissionStatus.Denied
                pendingPermissionAction = null
                refreshPermissionSnapshot()
                onShowPermissionIssue(
                    createPermissionIssue(
                        kind = PermissionKind.Notifications,
                        status = PermissionStatus.Denied,
                        blocking = true,
                    ),
                )
            }

            PermissionResult.DeniedPermanently -> {
                permissionOverrides[PermissionKind.Notifications] = PermissionStatus.RequiresSettings
                pendingPermissionAction = null
                refreshPermissionSnapshot()
                onShowPermissionIssue(
                    createPermissionIssue(
                        kind = PermissionKind.Notifications,
                        status = PermissionStatus.RequiresSettings,
                        blocking = true,
                    ),
                )
            }

            PermissionResult.ReturnedFromSettings -> {
                refreshPermissionSnapshot()
                resumePendingAction()
            }
        }
    }

    private fun handleVpnPermissionResult(result: PermissionResult) {
        when (result) {
            PermissionResult.Granted -> {
                permissionOverrides.remove(PermissionKind.VpnConsent)
                refreshPermissionSnapshot()
                resumePendingAction()
            }

            PermissionResult.Denied,
            PermissionResult.DeniedPermanently,
            -> {
                permissionOverrides[PermissionKind.VpnConsent] = PermissionStatus.Denied
                pendingPermissionAction = null
                refreshPermissionSnapshot()
                onShowPermissionIssue(
                    createPermissionIssue(
                        kind = PermissionKind.VpnConsent,
                        status = PermissionStatus.Denied,
                        blocking = true,
                    ),
                )
            }

            PermissionResult.ReturnedFromSettings -> {
                refreshPermissionSnapshot()
            }
        }
    }

    private fun handleBatteryOptimizationResult() {
        permissionOverrides.remove(PermissionKind.BatteryOptimization)
        refreshPermissionSnapshot()
        if (permissionState.value.snapshot.batteryOptimization == PermissionStatus.Granted) {
            resumePendingAction()
        } else if (
            pendingPermissionAction ==
            PermissionAction.RepairPermission(PermissionKind.BatteryOptimization)
        ) {
            pendingPermissionAction = null
        }
    }

    private fun resumePendingAction() {
        val action = pendingPermissionAction ?: return
        val snapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
        permissionState.update { it.copy(snapshot = snapshot) }
        val resolution =
            permissionCoordinator.resolve(
                action = action,
                configuredMode = mutations.currentUiState().configuredMode,
                snapshot = snapshot,
            )
        if (resolution.blockedBy == null) {
            permissionState.update { it.copy(issue = null) }
            continueResolvedAction(action, resolution.recommended)
        } else {
            requestPermissionFor(action = action, blockedBy = resolution.blockedBy, snapshot = snapshot)
        }
    }

    private fun continueResolvedAction(
        action: PermissionAction,
        recommended: List<PermissionKind>,
    ) {
        pendingPermissionAction = null
        when (action) {
            PermissionAction.StartConfiguredMode -> {
                onStartMode(mutations.currentUiState().configuredMode)
            }

            PermissionAction.StartVpnMode -> {
                onStartMode(Mode.VPN)
            }

            is PermissionAction.RepairPermission -> {
                if (action.kind == PermissionKind.BatteryOptimization && recommended.isEmpty()) {
                    refreshPermissionSnapshot()
                }
            }
        }
    }

    private fun mergeSnapshotWithOverrides(providerSnapshot: PermissionSnapshot): PermissionSnapshot {
        val notificationsStatus =
            when {
                providerSnapshot.notifications == PermissionStatus.Granted -> {
                    permissionOverrides.remove(PermissionKind.Notifications)
                    PermissionStatus.Granted
                }

                else -> {
                    permissionOverrides[PermissionKind.Notifications] ?: providerSnapshot.notifications
                }
            }
        val vpnStatus =
            when {
                providerSnapshot.vpnConsent == PermissionStatus.Granted -> {
                    permissionOverrides.remove(PermissionKind.VpnConsent)
                    PermissionStatus.Granted
                }

                else -> {
                    permissionOverrides[PermissionKind.VpnConsent] ?: providerSnapshot.vpnConsent
                }
            }

        return providerSnapshot.copy(
            notifications = notificationsStatus,
            vpnConsent = vpnStatus,
        )
    }

    private fun buildNotificationPermissionItem(status: PermissionStatus): PermissionItemUiState =
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

    private fun buildVpnPermissionItem(
        status: PermissionStatus,
        configuredMode: Mode,
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

    private fun buildBatteryPermissionItem(status: PermissionStatus): PermissionItemUiState =
        when (status) {
            PermissionStatus.Granted,
            PermissionStatus.NotApplicable,
            -> {
                PermissionItemUiState(
                    kind = PermissionKind.BatteryOptimization,
                    title = stringResolver.getString(R.string.permissions_battery_title),
                    subtitle =
                        stringResolver.getString(
                            if (status == PermissionStatus.Granted) {
                                BatteryOptimizationGuidance.readySubtitleRes(deviceManufacturer)
                            } else {
                                R.string.settings_permissions_battery_ready
                            },
                        ),
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
                    subtitle =
                        stringResolver.getString(
                            BatteryOptimizationGuidance.recommendedSubtitleRes(deviceManufacturer),
                        ),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_recommended),
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
                )
            }
        }

    private fun createPermissionIssue(
        kind: PermissionKind,
        status: PermissionStatus,
        blocking: Boolean,
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
                    message =
                        stringResolver.getString(
                            BatteryOptimizationGuidance.issueMessageRes(deviceManufacturer),
                        ),
                    recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
                    blocking = blocking,
                )
            }
        }

    private fun createAppSettingsIntent(): Intent = permissionPlatformBridge.createAppSettingsIntent()

    private fun createBatteryOptimizationIntent(): Intent = permissionPlatformBridge.createBatteryOptimizationIntent()
}

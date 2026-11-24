package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class MainPermissionActions(
    private val mutations: MainMutationRunner,
    private val permissionCoordinator: PermissionCoordinator,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val permissionPlatformBridge: PermissionPlatformBridge,
    private val stringResolver: StringResolver,
    val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val onStartMode: (Mode) -> Unit,
    private val onStartVerifiedVpn: () -> Unit,
    private val onShowPermissionIssue: (PermissionIssueUiState) -> Unit,
    private val onDismissError: () -> Unit,
) {
    private val permissionOverrides = mutableMapOf<PermissionKind, PermissionStatus>()
    private var pendingPermissionAction: PermissionAction? = null
    private var pendingVpnAction: PermissionAction? = null
    private var vpnRequestInProgress = false
    private val _vpnExplanationVisible = MutableStateFlow(false)
    val vpnExplanationVisible = _vpnExplanationVisible.asStateFlow()
    val localNetwork =
        MainLocalNetworkPermissionActions(
            mutations = mutations,
            permissionPlatformBridge = permissionPlatformBridge,
            stringResolver = stringResolver,
            permissionState = permissionState,
            refreshSnapshot = ::refreshPermissionSnapshot,
            onShowIssue = onShowPermissionIssue,
            resumeAction = ::resolvePermissionAction,
        )

    fun onVpnPermissionContinueRequested() {
        if (pendingVpnAction == null || vpnRequestInProgress) return
        _vpnExplanationVisible.value = false
        vpnRequestInProgress = true
        val intent = permissionPlatformBridge.prepareVpnPermissionIntent()
        if (intent == null) {
            handleVpnPermissionResult(PermissionResult.Granted)
        } else {
            mutations.trySend(MainEffect.RequestPermission(PermissionKind.VpnConsent, intent))
        }
    }

    fun onVpnPermissionDismissed() {
        pendingVpnAction = null
        _vpnExplanationVisible.value = false
    }

    fun onOpenVpnPermissionRequested() {
        showVpnPermissionDialog(pendingVpnAction ?: PermissionAction.RepairPermission(PermissionKind.VpnConsent))
    }

    private fun showVpnPermissionDialog(action: PermissionAction) {
        if (vpnRequestInProgress) return
        pendingVpnAction =
            if (action == PermissionAction.StartConfiguredMode) PermissionAction.StartVpnMode else action
        permissionState.update { it.copy(issue = null) }
        _vpnExplanationVisible.value = true
    }

    fun onRepairPermissionRequested(kind: PermissionKind) {
        if (kind == PermissionKind.LocalNetwork) {
            val snapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
            requestPermissionFor(
                action = PermissionAction.RepairPermission(kind),
                blockedBy = kind,
                snapshot = snapshot,
            )
        } else {
            resolvePermissionAction(PermissionAction.RepairPermission(kind))
        }
    }

    fun onPermissionResult(
        kind: PermissionKind,
        result: PermissionResult,
    ) {
        when (kind) {
            PermissionKind.LocalNetwork -> localNetwork.onResult(result)

            PermissionKind.Notifications -> handleNotificationPermissionResult(result)

            PermissionKind.VpnConsent -> handleVpnPermissionResult(result)

            PermissionKind.AlwaysOnVpn,
            PermissionKind.VpnLockdown,
            -> handleVpnSettingsResult()

            PermissionKind.BatteryOptimization -> handleBatteryOptimizationResult()
        }
    }

    fun onForeground() {
        refreshPermissionSnapshot()
        localNetwork.onForeground()
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
            action.isStartModeAction() &&
            mutations.currentUiState().connectionState == ConnectionState.Connecting
        ) {
            return
        }

        if (action.isStartModeAction()) {
            localNetwork.cancelDeferredAction()
            if (!vpnRequestInProgress) onVpnPermissionDismissed()
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
            continueResolvedAction(action)
            return
        }

        requestPermissionFor(action = action, blockedBy = blockedBy, snapshot = mergedSnapshot)
    }

    private fun requestPermissionFor(
        action: PermissionAction,
        blockedBy: PermissionKind,
        snapshot: PermissionSnapshot,
    ) {
        when (blockedBy) {
            PermissionKind.LocalNetwork -> {
                localNetwork.request(snapshot.localNetwork)
            }

            PermissionKind.Notifications -> {
                when (snapshot.notifications) {
                    PermissionStatus.RequiresSettings -> {
                        val issue =
                            createPermissionIssue(
                                kind = PermissionKind.Notifications,
                                status = PermissionStatus.RequiresSettings,
                                blocking = true,
                                stringResolver = stringResolver,
                            )
                        permissionState.update { it.copy(issue = issue, snapshot = snapshot) }
                        mutations.trySend(MainEffect.OpenAppSettings(createAppSettingsIntent()))
                    }

                    PermissionStatus.Denied,
                    PermissionStatus.RequiresSystemPrompt,
                    PermissionStatus.Unknown,
                    -> {
                        permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                        mutations.trySend(MainEffect.RequestPermission(kind = PermissionKind.Notifications))
                    }

                    PermissionStatus.Granted,
                    PermissionStatus.NotApplicable,
                    -> {
                        continueResolvedAction(action)
                    }
                }
            }

            PermissionKind.VpnConsent -> {
                showVpnPermissionDialog(action)
            }

            PermissionKind.AlwaysOnVpn,
            PermissionKind.VpnLockdown,
            -> {
                val issue =
                    createPermissionIssue(
                        kind = blockedBy,
                        status = snapshot.statusFor(blockedBy),
                        blocking = false,
                        stringResolver = stringResolver,
                    )
                permissionState.update { it.copy(issue = issue, snapshot = snapshot) }
                mutations.trySend(MainEffect.OpenAppSettings(createVpnSettingsIntent()))
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
                        stringResolver = stringResolver,
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
                        stringResolver = stringResolver,
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
        vpnRequestInProgress = false
        val action = pendingVpnAction
        when (result) {
            PermissionResult.Granted -> {
                pendingVpnAction = null
                permissionOverrides.remove(PermissionKind.VpnConsent)
                refreshPermissionSnapshot()
                if (action != null) continueResolvedAction(action)
            }

            PermissionResult.Denied,
            PermissionResult.DeniedPermanently,
            -> {
                if (action == null) return
                permissionOverrides[PermissionKind.VpnConsent] = PermissionStatus.Denied
                pendingPermissionAction = null
                refreshPermissionSnapshot()
                onShowPermissionIssue(
                    createPermissionIssue(
                        kind = PermissionKind.VpnConsent,
                        status = PermissionStatus.Denied,
                        blocking = action !is PermissionAction.RepairPermission,
                        stringResolver = stringResolver,
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

    private fun handleVpnSettingsResult() {
        refreshPermissionSnapshot()
        val pending = pendingPermissionAction
        if (pending is PermissionAction.RepairPermission &&
            (pending.kind == PermissionKind.AlwaysOnVpn || pending.kind == PermissionKind.VpnLockdown)
        ) {
            pendingPermissionAction = null
        } else {
            resumePendingAction()
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
            continueResolvedAction(action)
        } else {
            requestPermissionFor(action = action, blockedBy = resolution.blockedBy, snapshot = snapshot)
        }
    }

    private fun continueResolvedAction(action: PermissionAction) {
        pendingPermissionAction = null
        when (action) {
            PermissionAction.StartConfiguredMode -> {
                onStartMode(mutations.currentUiState().configuredMode)
            }

            PermissionAction.StartProxyMode -> {
                onStartMode(Mode.Proxy)
            }

            PermissionAction.StartVpnMode -> {
                onStartMode(Mode.VPN)
            }

            PermissionAction.StartVerifiedVpn -> {
                onStartVerifiedVpn()
            }

            is PermissionAction.RepairPermission -> {
                if (
                    action.kind == PermissionKind.BatteryOptimization ||
                    action.kind == PermissionKind.AlwaysOnVpn ||
                    action.kind == PermissionKind.VpnLockdown
                ) {
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
            localNetwork = localNetwork.mergeStatus(providerSnapshot.localNetwork),
            notifications = notificationsStatus,
            vpnConsent = vpnStatus,
        )
    }

    private fun createAppSettingsIntent(): Intent = permissionPlatformBridge.createAppSettingsIntent()

    private fun createVpnSettingsIntent(): Intent = permissionPlatformBridge.createVpnSettingsIntent()

    private fun createBatteryOptimizationIntent(): Intent = permissionPlatformBridge.createBatteryOptimizationIntent()
}

private fun PermissionAction.isStartModeAction(): Boolean =
    this is PermissionAction.StartConfiguredMode ||
        this is PermissionAction.StartProxyMode ||
        this is PermissionAction.StartVpnMode ||
        this is PermissionAction.StartVerifiedVpn

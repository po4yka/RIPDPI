package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.flow.MutableStateFlow

internal class MainLocalNetworkPermissionActions(
    private val mutations: MainMutationRunner,
    private val permissionPlatformBridge: PermissionPlatformBridge,
    private val stringResolver: StringResolver,
    private val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val refreshSnapshot: () -> Unit,
    private val onShowIssue: (PermissionIssueUiState) -> Unit,
    private val resumeAction: (PermissionAction) -> Unit,
) {
    private var deferredAction: PermissionAction? = null
    private var statusOverride: PermissionStatus? = null

    fun cancelDeferredAction() {
        deferredAction = null
    }

    fun request(status: PermissionStatus) {
        when (status) {
            PermissionStatus.RequiresSettings -> {
                mutations.trySend(
                    MainEffect.OpenAppSettings(permissionPlatformBridge.createAppSettingsIntent()),
                )
            }

            PermissionStatus.Granted, PermissionStatus.NotApplicable -> {
                onResult(PermissionResult.Granted)
            }

            else -> {
                mutations.trySend(MainEffect.RequestPermission(PermissionKind.LocalNetwork))
            }
        }
    }

    // A service event may explain the requirement, but cannot open a permission dialog.
    fun onRequired(action: PermissionAction? = null) {
        deferredAction = action
        refreshSnapshot()
        onShowIssue(
            createLocalNetworkPermissionIssue(permissionState.value.snapshot.localNetwork, true, stringResolver),
        )
    }

    fun onForeground() {
        if (deferredAction != null && permissionState.value.snapshot.localNetwork == PermissionStatus.Granted) {
            onResult(PermissionResult.ReturnedFromSettings)
        }
    }

    fun onResult(result: PermissionResult) {
        when (result) {
            PermissionResult.Granted, PermissionResult.ReturnedFromSettings -> {
                statusOverride = null
                refreshSnapshot()
                if (permissionState.value.snapshot.localNetwork == PermissionStatus.Granted) {
                    val action = deferredAction
                    deferredAction = null
                    action?.let(resumeAction)
                }
            }

            PermissionResult.Denied, PermissionResult.DeniedPermanently -> {
                statusOverride =
                    if (result == PermissionResult.DeniedPermanently) {
                        PermissionStatus.RequiresSettings
                    } else {
                        PermissionStatus.Denied
                    }
                onRequired(deferredAction)
            }
        }
    }

    fun mergeStatus(status: PermissionStatus): PermissionStatus {
        if (status == PermissionStatus.Granted || status == PermissionStatus.NotApplicable) statusOverride = null
        return statusOverride ?: status
    }
}

internal fun createLocalNetworkPermissionIssue(
    status: PermissionStatus,
    blocking: Boolean,
    strings: StringResolver,
): PermissionIssueUiState {
    val settings = status == PermissionStatus.RequiresSettings
    return PermissionIssueUiState(
        kind = PermissionKind.LocalNetwork,
        title = strings.getString(R.string.permissions_local_network_title),
        message =
            strings.getString(
                if (settings) {
                    R.string.permissions_local_network_settings
                } else {
                    R.string.permissions_local_network_needed
                },
            ),
        recovery = if (settings) PermissionRecovery.OpenSettings else PermissionRecovery.RetryPrompt,
        actionLabel =
            strings.getString(
                if (settings) {
                    R.string.settings_permission_action_open_settings
                } else {
                    R.string.settings_permission_action_allow
                },
            ),
        blocking = blocking,
    )
}

package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import android.os.Build
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import kotlinx.collections.immutable.toImmutableList

/**
 * Owns the permission-state slice of the detection screen: probing the required permissions and
 * deciding the planner action / missing list, plus recording a permission-request round.
 *
 * Mutates the screen state exclusively through the shared [DetectionCheckStateReducer]. Permission
 * probing is synchronous (no coroutine), matching the original VM.
 */
internal class DetectionPermissionStateOwner(
    private val reducer: DetectionCheckStateReducer,
    private val permissionChecker: DetectionPermissionChecker,
    private val preferences: DetectionCheckPreferenceStore,
) {
    fun onPermissionsResult() {
        markPermissionsRequested()
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        val states =
            requiredPermissions().map { permission ->
                DetectionPermissionPlanner.PermissionState(
                    permission = permission,
                    granted = permissionChecker.isPermissionGranted(permission),
                    shouldShowRationale = false,
                    wasRequestedBefore = preferences.wasPermissionRequested(permission),
                )
            }
        val action = DetectionPermissionPlanner.decideAction(states)
        val missing = DetectionPermissionPlanner.missingPermissions(states)
        reducer.mutate {
            it.copy(
                permissionAction = action,
                missingPermissions = missing.toImmutableList(),
            )
        }
    }

    /**
     * Opens the permission rationale instead of a check when the user has never seen it and the
     * permissions can still be requested, so the rationale appears when a check needs it, not on
     * screen entry. Returns true when the check must wait for the user's answer.
     */
    fun showRationaleBeforeCheck(): Boolean {
        val action = reducer.value.permissionAction
        val canRequest =
            action == DetectionPermissionPlanner.Action.REQUEST ||
                action == DetectionPermissionPlanner.Action.SHOW_RATIONALE
        if (!canRequest || preferences.wasOnboardingShown()) return false
        reducer.mutate { it.copy(showOnboarding = true) }
        return true
    }

    private fun markPermissionsRequested() {
        preferences.markPermissionsRequested(requiredPermissions())
    }

    companion object {
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                )
            } else {
                arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}

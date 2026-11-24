package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import android.os.Build
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import kotlinx.collections.immutable.toImmutableList

/**
 * Owns the permission-state slice of the detection screen: probing the required permissions and
 * deciding the next action, and recording Android permission results.
 *
 * Mutates the screen state exclusively through the shared [DetectionCheckStateReducer]. Permission
 * probing is synchronous (no coroutine), matching the original VM.
 */
internal class DetectionPermissionStateOwner(
    private val reducer: DetectionCheckStateReducer,
    private val permissionChecker: DetectionPermissionChecker,
    private val preferences: DetectionCheckPreferenceStore,
) {
    private val unansweredPermissions = mutableSetOf<String>()

    fun onPermissionsResult(
        grants: Map<String, Boolean>,
        shouldShowRationale: (String) -> Boolean,
    ) {
        // Android can return false for a permission whose prompt was dismissed without a choice.
        unansweredPermissions += requiredPermissions().filter { grants[it] == false }
        refreshPermissionState(shouldShowRationale)
    }

    fun refreshPermissionState(shouldShowRationale: (String) -> Boolean) {
        val states =
            requiredPermissions().map { permission ->
                val granted = permissionChecker.isPermissionGranted(permission)
                DetectionPermissionPlanner.PermissionState(
                    permission = permission,
                    granted = granted,
                    shouldShowRationale = !granted && shouldShowRationale(permission),
                    wasDeniedBefore = preferences.wasPermissionDenied(permission),
                )
            }
        val changed =
            states.filter {
                (it.granted && it.wasDeniedBefore) || (it.shouldShowRationale && !it.wasDeniedBefore)
            }
        preferences.recordPermissionResults(changed.associate { it.permission to it.granted })
        unansweredPermissions.removeAll(
            states.filter { it.granted || it.shouldShowRationale }.map { it.permission }.toSet(),
        )

        val requestable = DetectionPermissionPlanner.requestablePermissions(states)
        val request =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Manifest.permission.ACCESS_FINE_LOCATION in requestable
            ) {
                // Android 12+ requires both entries even when approximate access is already granted.
                requestable + Manifest.permission.ACCESS_COARSE_LOCATION
            } else {
                requestable
            }
        reducer.mutate {
            it.copy(
                permissionAction = DetectionPermissionPlanner.decideAction(states),
                permissionRequest = request.toImmutableList(),
                canOpenPermissionSettings =
                    states.any {
                        !it.granted && !it.shouldShowRationale &&
                            (it.wasDeniedBefore || it.permission in unansweredPermissions)
                    },
                showOnboarding = it.showOnboarding && request.isNotEmpty(),
            )
        }
    }

    /** Pauses a first check to explain the optional data before the user decides whether to grant it. */
    fun showRationaleBeforeCheck(): Boolean {
        if (reducer.value.permissionRequest.isEmpty() || preferences.wasOnboardingShown()) return false
        reducer.mutate { it.copy(showOnboarding = true) }
        return true
    }

    companion object {
        fun requiredPermissions(): Array<String> =
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

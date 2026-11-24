@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection

object DetectionPermissionPlanner {
    enum class Action {
        NONE,
        SHOW_RATIONALE,
        REQUEST,
        OPEN_SETTINGS,
    }

    data class PermissionState(
        val permission: String,
        val granted: Boolean,
        val shouldShowRationale: Boolean,
        val wasDeniedBefore: Boolean,
    )

    fun decideAction(permissions: List<PermissionState>): Action {
        val missing = permissions.filter { !it.granted }
        if (missing.isEmpty()) return Action.NONE
        if (missing.any { it.shouldShowRationale }) return Action.SHOW_RATIONALE
        if (missing.any { !it.wasDeniedBefore }) return Action.REQUEST
        return Action.OPEN_SETTINGS
    }

    fun requestablePermissions(permissions: List<PermissionState>): List<String> =
        permissions
            .filter { !it.granted && (it.shouldShowRationale || !it.wasDeniedBefore) }
            .map { it.permission }
}

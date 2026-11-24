package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner.Action
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionPermissionPlannerTest {
    @Test
    fun `all granted returns NONE`() {
        val states =
            listOf(
                PermissionState("perm1", granted = true, shouldShowRationale = false, wasDeniedBefore = true),
                PermissionState("perm2", granted = true, shouldShowRationale = false, wasDeniedBefore = true),
            )
        assertEquals(Action.NONE, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `rationale needed returns SHOW_RATIONALE`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = true, wasDeniedBefore = true),
            )
        assertEquals(Action.SHOW_RATIONALE, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `never requested returns REQUEST`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = false, wasDeniedBefore = false),
            )
        assertEquals(Action.REQUEST, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `previously denied returns OPEN_SETTINGS`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = false, wasDeniedBefore = true),
            )
        assertEquals(Action.OPEN_SETTINGS, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `empty list returns NONE`() {
        assertEquals(Action.NONE, DetectionPermissionPlanner.decideAction(emptyList()))
    }

    @Test
    fun `new and retryable permissions remain requestable beside a blocked permission`() {
        val states =
            listOf(
                PermissionState("granted", granted = true, shouldShowRationale = false, wasDeniedBefore = false),
                PermissionState("blocked", granted = false, shouldShowRationale = false, wasDeniedBefore = true),
                PermissionState("fresh", granted = false, shouldShowRationale = false, wasDeniedBefore = false),
                PermissionState("retry", granted = false, shouldShowRationale = true, wasDeniedBefore = true),
            )
        assertEquals(listOf("fresh", "retry"), DetectionPermissionPlanner.requestablePermissions(states))
    }

    @Test
    fun `rationale takes priority over request`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = true, wasDeniedBefore = true),
                PermissionState("perm2", granted = false, shouldShowRationale = false, wasDeniedBefore = false),
            )
        assertEquals(Action.SHOW_RATIONALE, DetectionPermissionPlanner.decideAction(states))
    }
}

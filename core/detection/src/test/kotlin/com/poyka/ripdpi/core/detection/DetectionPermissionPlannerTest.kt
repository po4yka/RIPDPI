package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner.Action
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPermissionPlannerTest {
    @Test
    fun `all granted returns NONE`() {
        val states =
            listOf(
                PermissionState("perm1", granted = true, shouldShowRationale = false, wasRequestedBefore = true),
                PermissionState("perm2", granted = true, shouldShowRationale = false, wasRequestedBefore = true),
            )
        assertEquals(Action.NONE, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `rationale needed returns SHOW_RATIONALE`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = true, wasRequestedBefore = true),
            )
        assertEquals(Action.SHOW_RATIONALE, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `never requested returns REQUEST`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = false, wasRequestedBefore = false),
            )
        assertEquals(Action.REQUEST, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `previously denied returns OPEN_SETTINGS`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = false, wasRequestedBefore = true),
            )
        assertEquals(Action.OPEN_SETTINGS, DetectionPermissionPlanner.decideAction(states))
    }

    @Test
    fun `empty list returns NONE`() {
        assertEquals(Action.NONE, DetectionPermissionPlanner.decideAction(emptyList()))
    }

    @Test
    fun `missingPermissions returns only non-granted`() {
        val states =
            listOf(
                PermissionState("perm1", granted = true, shouldShowRationale = false, wasRequestedBefore = true),
                PermissionState("perm2", granted = false, shouldShowRationale = false, wasRequestedBefore = false),
            )
        val missing = DetectionPermissionPlanner.missingPermissions(states)
        assertEquals(1, missing.size)
        assertTrue(missing.contains("perm2"))
    }

    @Test
    fun `rationale takes priority over request`() {
        val states =
            listOf(
                PermissionState("perm1", granted = false, shouldShowRationale = true, wasRequestedBefore = true),
                PermissionState("perm2", granted = false, shouldShowRationale = false, wasRequestedBefore = false),
            )
        assertEquals(Action.SHOW_RATIONALE, DetectionPermissionPlanner.decideAction(states))
    }
}

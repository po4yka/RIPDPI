package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [stickyRestartDecision].
 *
 * The decision TABLE (which input combination produces ABORT vs PROCEED) is
 * enforced here by calling the production function directly. The call ORDERING
 * (startForegroundService before the guard) is enforced by code review of
 * [RipDpiProxyService.onStartCommand].
 */
class RipDpiProxyServiceRestartTest {
    @Test
    fun `sticky restart with revoked permission on Tiramisu or above returns ABORT`() {
        assertEquals(
            StickyRestartDecision.ABORT,
            stickyRestartDecision(
                intentIsNull = true,
                sdkAtLeastTiramisu = true,
                notificationsGranted = false,
            ),
        )
    }

    @Test
    fun `sticky restart with permission granted returns PROCEED`() {
        assertEquals(
            StickyRestartDecision.PROCEED,
            stickyRestartDecision(
                intentIsNull = true,
                sdkAtLeastTiramisu = true,
                notificationsGranted = true,
            ),
        )
    }

    @Test
    fun `non-null intent returns PROCEED even with revoked permission`() {
        assertEquals(
            StickyRestartDecision.PROCEED,
            stickyRestartDecision(
                intentIsNull = false,
                sdkAtLeastTiramisu = true,
                notificationsGranted = false,
            ),
        )
    }

    @Test
    fun `pre-Tiramisu sticky restart returns PROCEED regardless of permission`() {
        assertEquals(
            StickyRestartDecision.PROCEED,
            stickyRestartDecision(
                intentIsNull = true,
                sdkAtLeastTiramisu = false,
                notificationsGranted = false,
            ),
        )
    }
}

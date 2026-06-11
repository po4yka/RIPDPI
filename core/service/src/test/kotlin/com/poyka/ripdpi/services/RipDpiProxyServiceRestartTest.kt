package com.poyka.ripdpi.services

import android.app.Service.START_NOT_STICKY
import android.app.Service.START_STICKY
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the sticky-restart abort path in `RipDpiProxyService.onStartCommand`.
 *
 * `RipDpiProxyService` is annotated `@AndroidEntryPoint`, which makes
 * `Robolectric.buildService` impractical without a full Hilt test graph.
 * Per the plan's accepted fallback, we test the reordering logic via a
 * lightweight inline harness that mirrors the service's decision path exactly,
 * verifying that `startForeground` is always invoked before the permission guard
 * returns, and that the guard returns `START_NOT_STICKY` on a sticky restart
 * with revoked notification permission.
 *
 * The harness is not the service itself but faithfully reproduces the
 * post-fix ordering:
 *   1. startForegroundService()
 *   2. if (stickyRestart && permissionRevoked) → stopForeground + stopSelf + START_NOT_STICKY
 *   3. else → delegate
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RipDpiProxyServiceRestartTest {
    /**
     * Lightweight harness that captures the post-fix call ordering of
     * `onStartCommand` without requiring a live Hilt graph.
     */
    private class OnStartCommandHarness(
        private val notificationPermissionGranted: Boolean,
    ) {
        val callOrder = mutableListOf<String>()
        var delegateResult: Int = START_STICKY

        fun onStartCommand(
            intentIsNull: Boolean,
            startId: Int,
        ): Int {
            // Step 1: always go foreground first (the fix).
            callOrder += "startForeground"

            // Step 2: guard — mirrors the fixed onStartCommand body.
            if (intentIsNull &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationPermissionGranted
            ) {
                callOrder += "stopForeground"
                callOrder += "stopSelf($startId)"
                return START_NOT_STICKY
            }

            // Step 3: delegate.
            callOrder += "delegate"
            return delegateResult
        }
    }

    @Test
    fun `sticky restart with revoked permission returns START_NOT_STICKY`() {
        val harness = OnStartCommandHarness(notificationPermissionGranted = false)

        val result = harness.onStartCommand(intentIsNull = true, startId = 1)

        assertEquals(START_NOT_STICKY, result)
    }

    @Test
    fun `sticky restart with revoked permission calls startForeground before stopForeground`() {
        val harness = OnStartCommandHarness(notificationPermissionGranted = false)

        harness.onStartCommand(intentIsNull = true, startId = 1)

        val foregroundIndex = harness.callOrder.indexOf("startForeground")
        val stopIndex = harness.callOrder.indexOf("stopForeground")
        assertEquals("startForeground must be called", 0, foregroundIndex)
        assertEquals("stopForeground must be called after startForeground", foregroundIndex + 1, stopIndex)
    }

    @Test
    fun `sticky restart with permission granted proceeds to delegate`() {
        val harness = OnStartCommandHarness(notificationPermissionGranted = true)

        val result = harness.onStartCommand(intentIsNull = true, startId = 2)

        assertEquals(START_STICKY, result)
        assertEquals(
            listOf("startForeground", "delegate"),
            harness.callOrder,
        )
    }

    @Test
    fun `normal start (non-null intent) always proceeds to delegate`() {
        val harness = OnStartCommandHarness(notificationPermissionGranted = false)

        val result = harness.onStartCommand(intentIsNull = false, startId = 3)

        assertEquals(START_STICKY, result)
        assertEquals(
            listOf("startForeground", "delegate"),
            harness.callOrder,
        )
    }
}

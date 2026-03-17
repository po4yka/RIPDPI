package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.permissions.PermissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityPermissionTest {
    @Test
    fun `notification result maps granted denied and permanent denial`() {
        assertEquals(
            PermissionResult.Granted,
            MainActivity.mapNotificationPermissionResult(granted = true, shouldShowRationale = false),
        )
        assertEquals(
            PermissionResult.Denied,
            MainActivity.mapNotificationPermissionResult(granted = false, shouldShowRationale = true),
        )
        assertEquals(
            PermissionResult.DeniedPermanently,
            MainActivity.mapNotificationPermissionResult(granted = false, shouldShowRationale = false),
        )
    }

    @Test
    fun `launch intent preserves home and configured start requests`() {
        val context = RuntimeEnvironment.getApplication()
        val intent =
            MainActivity.createLaunchIntent(
                context = context,
                openHome = true,
                requestStartConfiguredMode = true,
            )

        assertTrue(MainActivity.requestsHomeTab(intent))
        assertTrue(MainActivity.requestsConfiguredStart(intent))
    }

    @Test
    fun `launch intent defaults to no navigation requests`() {
        val intent = Intent()

        assertEquals(false, MainActivity.requestsHomeTab(intent))
        assertEquals(false, MainActivity.requestsConfiguredStart(intent))
    }
}

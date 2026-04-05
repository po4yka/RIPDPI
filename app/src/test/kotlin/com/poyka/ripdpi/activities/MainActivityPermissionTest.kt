package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.poyka.ripdpi.permissions.PermissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowVpnPrepareService::class])
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

    @Test
    fun `vpn result maps granted when system no longer requires consent`() {
        ShadowVpnPrepareService.prepareIntent = null

        assertEquals(
            PermissionResult.Granted,
            MainActivity.mapVpnPermissionResult(RuntimeEnvironment.getApplication()),
        )
    }

    @Test
    fun `vpn result maps denied when system still requires consent`() {
        ShadowVpnPrepareService.prepareIntent = Intent("shadow.vpn.permission")

        assertEquals(
            PermissionResult.Denied,
            MainActivity.mapVpnPermissionResult(RuntimeEnvironment.getApplication()),
        )
    }
}

@Implements(VpnService::class)
class ShadowVpnPrepareService private constructor() {
    companion object {
        var prepareIntent: Intent? = Intent("shadow.vpn.permission")

        @Implementation
        @JvmStatic
        fun prepare(
            @Suppress("UNUSED_PARAMETER") context: Context,
        ): Intent? = prepareIntent

        @Resetter
        @JvmStatic
        fun reset() {
            prepareIntent = Intent("shadow.vpn.permission")
        }
    }
}

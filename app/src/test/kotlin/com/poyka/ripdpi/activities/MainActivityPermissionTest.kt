package com.poyka.ripdpi.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.shortcuts.ExtraSelectGroupId
import com.poyka.ripdpi.shortcuts.ExtraSelectProfileId
import com.poyka.ripdpi.shortcuts.ExtraSelectorCapability
import com.poyka.ripdpi.shortcuts.SelectorShortcutCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            mapNotificationPermissionResult(granted = true, shouldShowRationale = false),
        )
        assertEquals(
            PermissionResult.Denied,
            mapNotificationPermissionResult(granted = false, shouldShowRationale = true),
        )
        assertEquals(
            PermissionResult.DeniedPermanently,
            mapNotificationPermissionResult(granted = false, shouldShowRationale = false),
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

        assertTrue(requestsHomeTab(intent))
        assertTrue(requestsConfiguredStart(intent))
    }

    @Test
    fun `configured mode launch uses non exported internal alias`() {
        val context = RuntimeEnvironment.getApplication()
        val intent =
            MainActivity.createLaunchIntent(
                context = context,
                requestStartConfiguredMode = true,
            )
        val component = ComponentName(context.packageName, internalVpnControlActivityClassName)

        assertEquals(component, intent.component)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
    }

    @Test
    fun `launch intent defaults to no navigation requests`() {
        val intent = Intent()

        assertEquals(false, requestsHomeTab(intent))
        assertEquals(false, requestsConfiguredStart(intent))
    }

    @Test
    fun `forged exported activity intent cannot control configured mode`() {
        val context = RuntimeEnvironment.getApplication()
        val forgedIntent =
            Intent(
                MainActivity.createLaunchIntent(
                    context = context,
                    requestStartConfiguredMode = true,
                    requestStopConfiguredMode = true,
                ),
            ).setClass(context, MainActivity::class.java)

        assertFalse(requestsConfiguredStart(forgedIntent))
        assertFalse(requestsConfiguredStop(forgedIntent))
    }

    @Test
    fun `public disconnect deep link cannot stop configured mode`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://disconnect"))

        assertFalse(requestsConfiguredStop(intent))
    }

    @Test
    fun `forged main activity selector extras cannot mutate selection`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val forgedIntent =
            Intent(context, MainActivity::class.java)
                .putExtra(ExtraSelectGroupId, "group")
                .putExtra(ExtraSelectProfileId, "profile")
        val selections = mutableListOf<Pair<String, String>>()
        val capability = SelectorShortcutCapability(context)

        val applied =
            applySelectorSelectionIntent(
                intent = forgedIntent,
                verifies = capability::verifies,
                select = { groupId, profileId -> selections += groupId to profileId },
            )

        assertFalse(applied)
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `signed selector extras remain accepted by main activity boundary`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val capability = SelectorShortcutCapability(context)
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(ExtraSelectGroupId, "group")
                .putExtra(ExtraSelectProfileId, "profile")
                .putExtra(ExtraSelectorCapability, capability.sign("group", "profile"))
        val selections = mutableListOf<Pair<String, String>>()

        val applied =
            applySelectorSelectionIntent(
                intent = intent,
                verifies = capability::verifies,
                select = { groupId, profileId -> selections += groupId to profileId },
            )

        assertTrue(applied)
        assertEquals(listOf("group" to "profile"), selections)
    }

    @Test
    fun `vpn result maps granted when system no longer requires consent`() {
        ShadowVpnPrepareService.prepareIntent = null

        assertEquals(
            PermissionResult.Granted,
            mapVpnPermissionResult(RuntimeEnvironment.getApplication()),
        )
    }

    @Test
    fun `vpn result maps denied when system still requires consent`() {
        ShadowVpnPrepareService.prepareIntent = Intent("shadow.vpn.permission")

        assertEquals(
            PermissionResult.Denied,
            mapVpnPermissionResult(RuntimeEnvironment.getApplication()),
        )
    }

    @Test
    fun `host command failure maps pending permission commands to terminal results`() {
        assertEquals(
            PermissionKind.LocalNetwork to PermissionResult.Denied,
            hostCommandFailurePermissionResult(MainActivityHostCommand.RequestLocalNetworkPermission),
        )
        assertEquals(
            PermissionKind.Notifications to PermissionResult.Denied,
            hostCommandFailurePermissionResult(MainActivityHostCommand.RequestNotificationsPermission),
        )
        assertEquals(
            PermissionKind.VpnConsent to PermissionResult.Denied,
            hostCommandFailurePermissionResult(
                MainActivityHostCommand.RequestVpnConsent(Intent("shadow.vpn.permission")),
            ),
        )
        assertEquals(
            PermissionKind.BatteryOptimization to PermissionResult.ReturnedFromSettings,
            hostCommandFailurePermissionResult(
                MainActivityHostCommand.RequestBatteryOptimization(Intent("shadow.battery.permission")),
            ),
        )
        assertEquals(
            null,
            hostCommandFailurePermissionResult(MainActivityHostCommand.OpenIntent(Intent("shadow.open"))),
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

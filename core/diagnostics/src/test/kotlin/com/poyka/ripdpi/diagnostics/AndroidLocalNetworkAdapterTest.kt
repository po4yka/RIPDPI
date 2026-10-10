package com.poyka.ripdpi.diagnostics

import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidLocalNetworkAdapterTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `device settings remain visible without telephony`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val snapshot = AndroidLocalNetworkContextCollector(context).capture()
        assertEquals(LocalObservationState.ENABLED, snapshot.device.airplaneMode)
        assertEquals(LocalSimScope.UNSUPPORTED, snapshot.sim.scope)
        assertEquals(LocalObservationState.UNSUPPORTED, snapshot.sim.mobileDataEnabled)
    }

    @Test
    fun `default data subscription adapter reads selected manager not default voice`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(7)
        val manager = context.getSystemService(TelephonyManager::class.java)
        val selected = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(manager).setTelephonyManagerForSubscriptionId(7, selected)
        shadowOf(selected).setDataEnabled(false)
        shadowOf(manager).setDataEnabled(true)
        val snapshot = AndroidLocalNetworkContextCollector(context).capture()
        assertEquals(LocalSimScope.DEFAULT_DATA, snapshot.sim.scope)
        assertEquals(LocalObservationState.DISABLED, snapshot.sim.mobileDataEnabled)
        assertEquals(LocalServiceState.PERMISSION_DENIED, snapshot.sim.voiceServiceState)
    }

    @Test
    @Config(sdk = [27])
    fun `older APIs return unsupported instead of false`() {
        val manager = context.getSystemService(TelephonyManager::class.java)
        val snapshot = AndroidLocalSimAdapter(context, manager).capture(7)
        assertEquals(LocalObservationState.UNSUPPORTED, snapshot.activeDataMatchesDefault)
        assertEquals(LocalObservationState.UNSUPPORTED, snapshot.roamingEnabled)
        assertEquals(LocalObservationState.UNSUPPORTED, snapshot.dataConnectionAllowed)
    }

    @Test
    @Config(sdk = [27])
    fun `API 27 device capture reports unsupported background restriction`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val snapshot = AndroidLocalNetworkContextCollector(context).capture()
        assertEquals(LocalObservationState.UNSUPPORTED, snapshot.device.backgroundRestricted)
        assertEquals(LocalObservationState.ENABLED, snapshot.device.airplaneMode)
    }

    @Test
    fun `invalid default selection does not report absent SIM`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        assertEquals(LocalSimScope.NOT_CONFIGURED, AndroidLocalNetworkContextCollector(context).capture().sim.scope)
    }
}

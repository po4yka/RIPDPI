package com.poyka.ripdpi.services

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class ForegroundServiceManifestContractTest {
    @Suppress("DEPRECATION")
    @Test
    fun `merged manifest declares the concrete runtime type for both foreground services`() {
        val context = RuntimeEnvironment.getApplication()

        listOf(RipDpiVpnService::class.java, RipDpiProxyService::class.java).forEach { serviceClass ->
            val serviceInfo =
                context.packageManager.getServiceInfo(
                    ComponentName(context, serviceClass),
                    0,
                )

            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, serviceInfo.foregroundServiceType)
        }
    }
}

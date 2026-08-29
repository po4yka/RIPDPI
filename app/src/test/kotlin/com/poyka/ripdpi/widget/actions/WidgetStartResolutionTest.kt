package com.poyka.ripdpi.widget.actions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.activities.internalVpnControlActivityClassName
import com.poyka.ripdpi.activities.requestsConfiguredStart
import com.poyka.ripdpi.activities.requestsHomeTab
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartPreflight
import com.poyka.ripdpi.services.ServiceStartPreflightResult
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WidgetStartResolutionTest {
    @Test
    fun `rejected widget start opens user recovery without an automatic retry`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        handleWidgetStartResult(
            context,
            ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing),
        )

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(internalVpnControlActivityClassName, intent.component?.className)
        assertTrue(requestsHomeTab(intent))
        assertFalse(requestsConfiguredStart(intent))
    }

    @Test
    fun `accepted widget start does not open the app`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        handleWidgetStartResult(context, ServiceStartResult.Accepted(Mode.Proxy))

        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `local network preflight opens foreground recovery before service dispatch`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val serviceController = RecordingServiceController()

            startServiceFromWidget(
                context = context,
                mode = Mode.Proxy,
                serviceStartPreflight =
                    ServiceStartPreflight {
                        ServiceStartPreflightResult.LocalNetworkPermissionRequired
                    },
                serviceController = serviceController,
            )

            val intent = shadowOf(context).nextStartedActivity
            assertEquals(internalVpnControlActivityClassName, intent.component?.className)
            assertTrue(requestsHomeTab(intent))
            assertTrue(requestsConfiguredStart(intent))
            assertEquals(0, serviceController.startCount)
        }

    private class RecordingServiceController : ServiceController {
        var startCount = 0

        override fun start(mode: Mode): ServiceStartResult {
            startCount += 1
            return ServiceStartResult.Accepted(mode)
        }

        override fun stop() = Unit
    }
}

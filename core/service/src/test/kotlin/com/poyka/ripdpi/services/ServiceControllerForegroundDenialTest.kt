package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
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
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [ShadowServiceControllerVpnPrepareService::class])
class ServiceControllerForegroundDenialTest {
    @Test
    fun foregroundServiceDenialRejectsProxyStartWithoutReportingRunning() {
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
        val starter =
            RecordingForegroundServiceStarter {
                throw IllegalStateException("foreground start denied")
            }
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        val result = controller.start(Mode.Proxy)

        assertTrue(result is ServiceStartResult.Rejected)
        assertTrue(
            (result as ServiceStartResult.Rejected).reason is ServiceStartRejectionReason.ForegroundServiceBlocked,
        )
        assertEquals(AppStatus.Halted to Mode.Proxy, serviceStateStore.status.value)
        assertEquals(1, starter.startCount)
        assertTrue(serviceStateStore.eventHistory.isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU], shadows = [ShadowServiceControllerVpnPrepareService::class])
    fun missingNotificationsDoNotBlockForegroundIntent() {
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        val result = controller.start(Mode.Proxy)

        assertEquals(ServiceStartResult.Accepted(Mode.Proxy), result)
        assertEquals(1, starter.startCount)
        assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
    }

    @Test
    fun missingVpnConsentRejectsVpnStartBeforeForegroundIntent() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = Intent("shadow.vpn.permission")
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        val result = controller.start(Mode.VPN)

        assertEquals(
            ServiceStartResult.Rejected(
                mode = Mode.VPN,
                reason = ServiceStartRejectionReason.VpnConsentMissing,
            ),
            result,
        )
        assertEquals(0, starter.startCount)
    }

    @Test
    fun acceptedProxyStartIssuesForegroundIntent() {
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        val result = controller.start(Mode.Proxy)

        assertEquals(ServiceStartResult.Accepted(Mode.Proxy), result)
        assertEquals(1, starter.startCount)
        assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
    }

    @Test
    fun acceptedVpnStartIssuesForegroundIntent() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        val result = controller.start(Mode.VPN)

        assertEquals(ServiceStartResult.Accepted(Mode.VPN), result)
        assertEquals(1, starter.startCount)
        assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
    }

    @Test
    fun explicitStopClearsWasRunningAtUpdateFlag() {
        val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = RecordingForegroundServiceStarter(),
                bootSessionStateStore = store,
            )

        controller.stop()

        assertFalse(store.wasRunningAtUpdate())
    }
}

private class InMemoryBootSessionStateStore : BootSessionStateStore {
    private var pointer: BootSessionPointer? = null
    private var wasRunningAtUpdate = false

    override fun lastSession(): BootSessionPointer? = pointer

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) {
        pointer = BootSessionPointer(profileId = profileId, mode = mode)
    }

    override fun clear() {
        pointer = null
    }

    override fun wasRunningAtUpdate(): Boolean = wasRunningAtUpdate

    override fun setWasRunningAtUpdate(value: Boolean) {
        wasRunningAtUpdate = value
    }
}

private class RecordingForegroundServiceStarter(
    private val startBlock: () -> Unit = {},
) : ForegroundServiceStarter {
    var startCount = 0
        private set
    var lastIntent: Intent? = null
        private set

    override fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        startCount += 1
        lastIntent = intent
        startBlock()
    }
}

@Implements(VpnService::class)
class ShadowServiceControllerVpnPrepareService private constructor() {
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

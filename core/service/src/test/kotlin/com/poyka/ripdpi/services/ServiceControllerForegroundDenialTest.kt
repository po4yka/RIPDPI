package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.os.Build
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ServiceControllerForegroundDenialTest {
    @Test
    fun foregroundServiceDenialDoesNotReportProxyRunning() {
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
            )

        controller.start(Mode.Proxy)

        assertEquals(AppStatus.Halted to Mode.Proxy, serviceStateStore.status.value)
        assertEquals(1, starter.startCount)
        assertTrue(serviceStateStore.eventHistory.isEmpty())
    }
}

private class RecordingForegroundServiceStarter(
    private val startBlock: () -> Unit,
) : ForegroundServiceStarter {
    var startCount = 0
        private set

    override fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        startCount += 1
        startBlock()
    }
}

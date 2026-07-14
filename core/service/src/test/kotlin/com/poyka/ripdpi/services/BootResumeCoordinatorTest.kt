package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeCoordinatorTest {
    @Test
    fun `accepted boot resume starts service before publishing reconnecting`() =
        runTest {
            val pointer = BootSessionPointer(profileId = "default", mode = Mode.VPN)
            val bootStore = FakeBootSessionStateStore(pointer = pointer)
            val serviceStateStore = DefaultServiceStateStore()
            val serviceController = RecordingServiceController()
            val coordinator = coordinator(bootStore, serviceController, serviceStateStore, resumable = true)

            coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, serviceStateStore.status.value)
        }

    @Test
    fun `stale boot pointer is cleared without starting a foreground service`() =
        runTest {
            val bootStore =
                FakeBootSessionStateStore(
                    pointer = BootSessionPointer(profileId = "deleted", mode = Mode.Proxy),
                )
            val serviceStateStore = DefaultServiceStateStore()
            val serviceController = RecordingServiceController()
            val coordinator = coordinator(bootStore, serviceController, serviceStateStore, resumable = false)

            coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertTrue(bootStore.cleared)
            assertNull(bootStore.lastSession())
            assertTrue(serviceController.startedModes.isEmpty())
            assertEquals(AppStatus.Halted to Mode.VPN, serviceStateStore.status.value)
        }

    @Test
    fun `package replacement consumes running flag before resuming`() =
        runTest {
            val bootStore =
                FakeBootSessionStateStore(
                    pointer = BootSessionPointer(profileId = "default", mode = Mode.Proxy),
                    runningAtUpdate = true,
                )
            val serviceStateStore = DefaultServiceStateStore()
            val serviceController = RecordingServiceController()
            val coordinator = coordinator(bootStore, serviceController, serviceStateStore, resumable = true)

            coordinator.resume(Intent.ACTION_MY_PACKAGE_REPLACED)

            assertEquals(listOf(false), bootStore.runningFlagWrites)
            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        }

    private fun coordinator(
        bootStore: BootSessionStateStore,
        serviceController: ServiceController,
        serviceStateStore: DefaultServiceStateStore,
        resumable: Boolean,
    ): BootResumeCoordinator =
        BootResumeCoordinator(
            bootSessionStateStore = bootStore,
            profileGuard =
                object : BootResumeProfileGuard {
                    override suspend fun isResumable(profileId: String): Boolean = resumable
                },
            serviceController = serviceController,
            serviceStateStore = serviceStateStore,
        )
}

private class FakeBootSessionStateStore(
    private var pointer: BootSessionPointer?,
    private var runningAtUpdate: Boolean = false,
) : BootSessionStateStore {
    var cleared: Boolean = false
        private set
    val runningFlagWrites = mutableListOf<Boolean>()

    override fun lastSession(): BootSessionPointer? = pointer

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) {
        pointer = BootSessionPointer(profileId = profileId, mode = mode)
    }

    override fun clear() {
        cleared = true
        pointer = null
    }

    override fun wasRunningAtUpdate(): Boolean = runningAtUpdate

    override fun setWasRunningAtUpdate(value: Boolean) {
        runningFlagWrites += value
        runningAtUpdate = value
    }
}

private class RecordingServiceController : ServiceController {
    val startedModes = mutableListOf<Mode>()

    override fun start(mode: Mode): ServiceStartResult {
        startedModes += mode
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() = Unit
}

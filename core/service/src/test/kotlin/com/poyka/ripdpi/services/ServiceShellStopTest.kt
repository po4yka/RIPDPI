package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellStopTest {
    @Test
    fun `accepted notification stop invalidates diagnostics resume lease`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            val lease = tracker.captureResumeLease()
            val store = NotificationStopBootStore(running = true)
            val recorder = AcceptedUserStopRecorder(store, tracker, ServiceIntentArbiter())
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = ServiceIntentArbiter(),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = recorder::record,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(notificationStopAction, 12)

            val ownership = tracker.ownership(lease)
            assertFalse(store.wasRunningAtUpdate())
            assertTrue(ownership is ResumeLeaseOwnership.Superseded)
            assertEquals(UserRuntimeIntent.Stopped, (ownership as ResumeLeaseOwnership.Superseded).intent)
        }

    @Test
    fun `rejected notification stop preserves diagnostics resume lease`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            val lease = tracker.captureResumeLease()
            val store = NotificationStopBootStore(running = true)
            val recorder = AcceptedUserStopRecorder(store, tracker, ServiceIntentArbiter())
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = ServiceIntentArbiter(),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    isStopAllowed = { false },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = recorder::record,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(notificationStopAction, 13)

            assertTrue(store.wasRunningAtUpdate())
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }
}

private class NotificationStopBootStore(
    private var running: Boolean,
) : BootSessionStateStore {
    private var pointer: BootSessionPointer? = null

    override fun lastSession(): BootSessionPointer? = pointer

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) {
        pointer = BootSessionPointer(profileId, mode)
    }

    override fun clear() {
        pointer = null
    }

    override fun wasRunningAtUpdate(): Boolean = running

    override fun setWasRunningAtUpdate(value: Boolean) {
        running = value
    }
}

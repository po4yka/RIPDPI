package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val stopped = CompletableDeferred<Unit>()
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val store = NotificationStopBootStore(running = true)
            val recorder =
                AcceptedUserStopRecorder(
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    bootSessionStateStore = store,
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = arbiter,
                )
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = { command -> recorder.record(command).also { stopped.complete(Unit) } },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                notificationStopAction,
                12,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
                activation = null,
                stopSnapshot = authority.snapshotAuthority(),
            )

            runCurrent()
            stopped.await()
            runCurrent()
            val ownership = tracker.ownership(lease)
            assertFalse(store.wasRunningAtUpdate())
            assertTrue(ownership is ResumeLeaseOwnership.Superseded)
            assertEquals(UserRuntimeIntent.Stopped, (ownership as ResumeLeaseOwnership.Superseded).intent)
        }

    @Test
    fun `rejected notification stop preserves diagnostics resume lease`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val stopped = CompletableDeferred<Unit>()
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val store = NotificationStopBootStore(running = true)
            val recorder =
                AcceptedUserStopRecorder(
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    bootSessionStateStore = store,
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = arbiter,
                )
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    isStopAllowed = { false },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = { command -> recorder.record(command).also { stopped.complete(Unit) } },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                notificationStopAction,
                13,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
                activation = null,
                stopSnapshot = authority.snapshotAuthority(),
            )

            runCurrent()
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

package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellDelegateTest {
    @Test
    fun `explicit user start prepares selection before runtime start`() =
        runTest {
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = {},
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startAction, 1)
            runCurrent()

            assertEquals(listOf("prepare", "start"), operations)
        }

    @Test
    fun `transport failover restart preserves prepared fallback selection`() =
        runTest {
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = {},
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> operations += "fallback-restart" },
                        ),
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 11L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
            )
            delegate.onStartCommand(startAction, 2)
            runCurrent()

            assertEquals(listOf("fallback-restart", "prepare", "start"), operations)
        }

    @Test
    fun `transport failover without a target rejects the tracked request`() =
        runTest {
            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = {},
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> },
                            reject = rejectedRequests::add,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 13L,
            )

            assertEquals(listOf(13L), rejectedRequests)
        }

    @Test
    fun `queued transport failover is rejected when command consumer stops`() =
        runTest {
            val keepConsumerBusy = CompletableDeferred<Unit>()
            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { keepConsumerBusy.await() },
                    onStop = {},
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> },
                            reject = rejectedRequests::add,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            delegate.onStartCommand(startAction, 1)
            runCurrent()

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 14L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
            )
            backgroundScope.cancel()
            runCurrent()

            assertEquals(listOf(14L), rejectedRequests)
        }

    @Test
    fun `startup fallback start bypasses explicit user preparation`() =
        runTest {
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = {},
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startupFallbackStartAction, 1)
            runCurrent()

            assertEquals(listOf("start"), operations)
        }

    @Test
    fun `start commands forward their ids to protected runtime cleanup`() =
        runTest {
            val startIds = mutableListOf<Int>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId -> startIds += startId },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startAction, 7)
            delegate.onStartCommand(startupFallbackStartAction, 8)
            runCurrent()

            assertEquals(listOf(7, 8), startIds)
        }

    @Test
    fun `fallback queued during failed cleanup survives the older stop request`() =
        runTest {
            var latestStartId = 0
            var serviceStopped = false
            var replacementRunning = false
            lateinit var delegate: ServiceShellDelegate
            delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId ->
                        if (startId == 1) {
                            latestStartId = 2
                            delegate.onStartCommand(startupFallbackStartAction, latestStartId)
                            requestStopSelfWithFallback(
                                stopSelfStartId = startId,
                                stopSelfResult = { stoppingStartId ->
                                    if (stoppingStartId == latestStartId) serviceStopped = true
                                    stoppingStartId == latestStartId
                                },
                                stopSelf = { serviceStopped = true },
                            )
                        } else {
                            replacementRunning = true
                        }
                    },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            latestStartId = 1
            delegate.onStartCommand(startAction, latestStartId)
            runCurrent()

            assertFalse(serviceStopped)
            assertTrue(replacementRunning)
        }

    @Test
    fun `duplicate explicit start does not reprepare a running runtime`() =
        runTest {
            var running = false
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {
                        operations += "start"
                        running = true
                    },
                    onStop = {},
                    beforeUserStart = { operations += "prepare" },
                    shouldPrepareUserStart = { !running },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startAction, 1)
            runCurrent()
            delegate.onStartCommand(startAction, 2)
            runCurrent()

            assertEquals(listOf("prepare", "start", "start"), operations)
        }

    @Test
    fun `manual start received while halted keeps VLESS preparation behind a queued fallback`() =
        runTest {
            var running = false
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { action, _ ->
                        operations += if (action == startupFallbackStartAction) "fallback" else "manual"
                        running = true
                    },
                    onStop = {},
                    beforeUserStart = { operations += "prepare-vless" },
                    shouldPrepareUserStart = { !running },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startupFallbackStartAction, 1)
            delegate.onStartCommand(startAction, 2)
            runCurrent()

            assertEquals(listOf("fallback", "prepare-vless", "manual"), operations)
        }

    @Test
    fun proxyShellDelegatesStartAndStopActions() =
        runTest {
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val startResult = delegate.onStartCommand(startAction, 1)
            runCurrent()
            val stopResult = delegate.onStartCommand(stopAction, 7)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, startResult)
            assertEquals(android.app.Service.START_NOT_STICKY, stopResult)
            assertEquals(1, startCalls)
            assertEquals(listOf(7), stopIds)
        }

    @Test
    fun `stop action is rejected when service policy forbids disconnect`() =
        runTest {
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(stopAction, 7)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `transport failover recomposes without a stop while lockdown forbids disconnect`() =
        runTest {
            var startCalls = 0
            var restartCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> restartCalls += 1 },
                        ),
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result =
                delegate.onStartCommand(
                    transportFailoverRestartAction,
                    8,
                    transportFailoverRequestId = 12L,
                    transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, restartCalls)
            assertEquals(0, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `stop action checks current policy for stale notification intent`() =
        runTest {
            var stopAllowed = true
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    isStopAllowed = { stopAllowed },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            stopAllowed = false
            val result = delegate.onStartCommand(notificationStopAction, 9)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `proxy notification stop remains allowed by default`() =
        runTest {
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(notificationStopAction, 11)
            runCurrent()

            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertEquals(listOf(11), stopIds)
        }

    @Test
    fun `accepted notification stop invalidates diagnostics resume lease`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            val lease = tracker.captureResumeLease()
            val store = NotificationStopBootStore(running = true)
            val recorder = AcceptedUserStopRecorder(store, tracker, ServiceIntentArbiter())
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = {},
                    onAcceptedStop = recorder::record,
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
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = {},
                    isStopAllowed = { false },
                    onAcceptedStop = recorder::record,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(notificationStopAction, 13)

            assertTrue(store.wasRunningAtUpdate())
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun `diagnostics stop preserves its own resume lease`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            val lease = tracker.captureResumeLease()
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { stopIds += it },
                    onAcceptedStop = tracker::recordAcceptedStop,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(diagnosticsStopAction, 14)
            runCurrent()

            assertEquals(listOf(14), stopIds)
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun `accepted start restores request order after delayed stop acceptance`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            val lease = tracker.captureResumeLease()
            tracker.withUserStart(action = {})
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = {},
                    onAcceptedStart = tracker::recordAcceptedStart,
                    onAcceptedStop = tracker::recordAcceptedStop,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(stopAction, 15)
            delegate.onStartCommand(startAction, 16)
            runCurrent()

            val ownership = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
            assertEquals(UserRuntimeIntent.Running, ownership.intent)
        }

    @Test
    fun `stale diagnostics compensation is skipped after accepted start`() =
        runTest {
            val tracker = RuntimeResumeIntentTracker()
            tracker.recordAcceptedStop()
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    onAcceptedStart = tracker::recordAcceptedStart,
                    isCompensatingStopCurrent = tracker::isCurrentIntentStopped,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(startAction, 17)
            val result = delegate.onStartCommand(diagnosticsCompensatingStopAction, 18)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `null action triggers start for sticky service restart`() =
        runTest {
            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(null, 1)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
        }

    @Test
    fun `android always-on action triggers recovery start`() =
        runTest {
            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(android.net.VpnService.SERVICE_INTERFACE, 2)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
        }

    @Test
    fun `android always-on action uses recovery barrier instead of user start`() =
        runTest {
            val events = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { events += "user-start" },
                    onStartWithId = { _, _ ->
                        events += "recover"
                        events += "runtime-start"
                    },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(android.net.VpnService.SERVICE_INTERFACE, 3)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(listOf("recover", "runtime-start"), events)
        }

    @Test
    fun `dedicated recovery actions use recovery barrier`() =
        runTest {
            val recoveredActions = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { error("recovery must not use the user-start path") },
                    onStartWithId = { _, _ -> recoveredActions += "recovered" },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            listOf(
                bootRecoveryStartAction,
                packageReplacedRecoveryStartAction,
                processDeathRecoveryStartAction,
            ).forEachIndexed { index, action ->
                assertEquals(android.app.Service.START_STICKY, delegate.onStartCommand(action, index + 10))
                runCurrent()
            }

            assertEquals(listOf("recovered", "recovered", "recovered"), recoveredActions)
        }

    @Test
    fun `unknown action is ignored without stopping service`() =
        runTest {
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand("unknown", 9)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `onRevoke delegates to revoke handler`() =
        runTest {
            var revokeCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = {},
                    onRevoke = { revokeCalls += 1 },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onRevoke()
            runCurrent()

            assertEquals(1, revokeCalls)
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

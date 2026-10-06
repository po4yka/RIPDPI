package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt
import com.poyka.ripdpi.data.RuntimeAuthoritySnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.data.testPauseAuthority
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
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            start.deliver(
                delegate,
                startAction,
                1,
            )
            runCurrent()

            assertEquals(listOf("prepare", "start"), operations)
        }

    @Test
    fun `transport failover restart preserves prepared fallback selection`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val failover = fixture.continuation(fixture.seedRunning())

            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = { _, _ -> },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> operations += "fallback-restart" },
                        ),
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            failover.deliver(
                delegate,
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 11L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
            )
            val start = fixture.reserveStart()
            start.deliver(
                delegate,
                startAction,
                2,
            )
            runCurrent()

            assertEquals(listOf("fallback-restart", "prepare", "start"), operations)
        }

    @Test
    fun `transport failover without a target rejects the tracked request`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)

            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> },
                            reject = rejectedRequests::add,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val command = fixture.captureUnstamped()
            command.deliver(
                delegate,
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 13L,
            )

            assertEquals(listOf(13L), rejectedRequests)
        }

    @Test
    fun `queued transport failover is rejected when command consumer stops`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            val keepConsumerBusy = CompletableDeferred<Unit>()
            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { keepConsumerBusy.await() },
                    onStop = { _, _ -> },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> },
                            reject = rejectedRequests::add,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )
            start.deliver(
                delegate,
                startAction,
                1,
            )
            runCurrent()

            start.deliver(
                delegate,
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
            val fixture = ShellCommandFixture(Mode.VPN)
            val fallback = fixture.reserveStart()

            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            fallback.deliver(
                delegate,
                startupFallbackStartAction,
                1,
            )
            runCurrent()

            assertEquals(listOf("start"), operations)
        }

    @Test
    fun `start commands forward their ids to protected runtime cleanup`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            val startIds = mutableListOf<Int>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId -> startIds += startId },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            start.deliver(
                delegate,
                startAction,
                7,
            )
            start.deliver(
                delegate,
                startupFallbackStartAction,
                8,
            )
            runCurrent()

            assertEquals(listOf(7, 8), startIds)
        }

    @Test
    fun `fallback queued during failed cleanup survives the older stop request`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            var latestStartId = 0
            var serviceStopped = false
            var replacementRunning = false
            lateinit var delegate: ServiceShellDelegate
            delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId ->
                        if (startId == 1) {
                            latestStartId = 2
                            start.deliver(
                                delegate,
                                startupFallbackStartAction,
                                latestStartId,
                            )
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
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            latestStartId = 1
            start.deliver(
                delegate,
                startAction,
                latestStartId,
            )
            runCurrent()

            assertFalse(serviceStopped)
            assertTrue(replacementRunning)
        }

    @Test
    fun `duplicate explicit start does not reprepare a running runtime`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            var running = false
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {
                        operations += "start"
                        running = true
                    },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare" },
                    shouldPrepareUserStart = { !running },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            start.deliver(
                delegate,
                startAction,
                1,
            )
            runCurrent()
            start.deliver(
                delegate,
                startAction,
                2,
            )
            runCurrent()

            assertEquals(listOf("prepare", "start", "start"), operations)
        }

    @Test
    fun `manual start received while halted keeps VLESS preparation behind a queued fallback`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val start = fixture.reserveStart()

            var running = false
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { action, _ ->
                        operations += if (action == startupFallbackStartAction) "fallback" else "manual"
                        running = true
                    },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare-vless" },
                    shouldPrepareUserStart = { !running },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            start.deliver(
                delegate,
                startupFallbackStartAction,
                1,
            )
            start.deliver(
                delegate,
                startAction,
                2,
            )
            runCurrent()

            assertEquals(listOf("fallback", "prepare-vless", "manual"), operations)
        }

    @Test
    fun proxyShellDelegatesStartAndStopActions() =
        runTest {
            val fixture = ShellCommandFixture(Mode.Proxy)
            val start = fixture.reserveStart()

            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val startResult =
                start.deliver(
                    delegate,
                    startAction,
                    1,
                )
            runCurrent()
            val stop = fixture.reserveStop()
            val stopResult =
                stop.deliver(
                    delegate,
                    stopAction,
                    7,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, startResult)
            assertEquals(android.app.Service.START_NOT_STICKY, stopResult)
            assertEquals(1, startCalls)
            assertEquals(listOf(7), stopIds)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellStopOwnershipTest {
    @Test
    fun `stop action is rejected when service policy forbids disconnect`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val stop = fixture.reserveStop()

            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                stop.deliver(
                    delegate,
                    stopAction,
                    7,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `transport failover recomposes without a stop while lockdown forbids disconnect`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val failover = fixture.continuation(fixture.seedRunning())

            var startCalls = 0
            var restartCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ -> restartCalls += 1 },
                        ),
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                failover.deliver(
                    delegate,
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
            val fixture = ShellCommandFixture(Mode.VPN)
            val stop = fixture.reserveStop()

            var stopAllowed = true
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    isStopAllowed = { stopAllowed },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            stopAllowed = false
            val result =
                stop.deliver(
                    delegate,
                    notificationStopAction,
                    9,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `proxy notification stop remains allowed by default`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.Proxy)
            val stop = fixture.reserveStop()

            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                stop.deliver(
                    delegate,
                    notificationStopAction,
                    11,
                )
            runCurrent()

            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertEquals(listOf(11), stopIds)
        }

    @Test
    fun `diagnostics stop preserves its own resume lease`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            fixture.seedRunning()
            val stop = fixture.captureDiagnosticsStop()

            val tracker =
                RuntimeResumeIntentTracker(fixture.authority)
            val lease = tracker.captureResumeLease()
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = { command ->
                                tracker.recordAcceptedStop()
                                (command as? AcceptedServiceStop.Prepared)?.reference
                                    ?: fixture.authority.reference()
                            },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            stop.deliver(
                delegate,
                diagnosticsStopAction,
                14,
            )
            runCurrent()

            assertEquals(listOf(14), stopIds)
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun `diagnostics stop durably records its cause before runtime teardown`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            fixture.seedRunning()
            val stop = fixture.captureDiagnosticsStop()

            val operations = mutableListOf<String>()
            val store = RecordingServiceStopArtifactWriteStore(operations)
            val recorder = RoomServiceStopProvenanceRecorder(store, AndroidRuntimeEvidenceClock { 321L })
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, provenance ->
                        recorder.record(Mode.VPN, provenance)
                        operations += "stop"
                    },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            stop.deliver(
                delegate,
                diagnosticsStopAction,
                15,
            )
            runCurrent()

            val event = requireNotNull(store.event)
            assertEquals(
                listOf(
                    "persist",
                    "stop",
                    "android_service_control",
                    "service_lifecycle",
                    "event=service_stop_requested reason=diagnostics_raw_path_scan initiator=diagnostics_runtime",
                    "vpn",
                    "321",
                ),
                operations +
                    listOf(
                        event.source,
                        event.subsystem,
                        event.message,
                        event.mode,
                        event.createdAt.toString(),
                    ),
            )
        }

    @Test
    fun `accepted start restores request order after delayed stop acceptance`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.Proxy)

            val tracker =
                RuntimeResumeIntentTracker(fixture.authority)
            val lease = tracker.captureResumeLease()
            tracker.withUserStart(action = {})
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = { _, _ -> },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStart = tracker::recordAcceptedStart,
                            acceptedStop = { command ->
                                tracker.recordAcceptedStop()
                                (command as? AcceptedServiceStop.Prepared)?.reference
                                    ?: fixture.authority.reference()
                            },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val stop = fixture.reserveStop()
            stop.deliver(
                delegate,
                stopAction,
                15,
            )
            val start = fixture.reserveStart()
            start.deliver(
                delegate,
                startAction,
                16,
            )
            runCurrent()

            val ownership = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
            assertEquals(UserRuntimeIntent.Running, ownership.intent)
        }

    @Test
    fun `stale diagnostics compensation is skipped after accepted start`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.Proxy)
            val compensation = fixture.reserveStop().withoutProcessStamp()

            val tracker =
                RuntimeResumeIntentTracker(fixture.authority)
            tracker.recordAcceptedStop()
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStart = tracker::recordAcceptedStart,
                            acceptedStop = { command ->
                                (command as? AcceptedServiceStop.Prepared)?.reference
                                    ?: fixture.authority.reference()
                            },
                        ),
                    isCompensatingStopCurrent = tracker::isCurrentIntentStopped,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val start = fixture.reserveStart()
            start.deliver(
                delegate,
                startAction,
                17,
            )
            val result =
                compensation.deliver(
                    delegate,
                    diagnosticsCompensatingStopAction,
                    18,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellRecoveryTest {
    @Test
    fun `null action triggers start for sticky service restart`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val recovery = fixture.recovery()

            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                recovery.deliver(
                    delegate,
                    null,
                    1,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
        }

    @Test
    fun `android always-on action triggers recovery start`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val recovery = fixture.recovery()

            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                recovery.deliver(
                    delegate,
                    android.net.VpnService.SERVICE_INTERFACE,
                    2,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
        }

    @Test
    fun `android always-on action uses recovery barrier instead of user start`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val recovery = fixture.recovery()

            val events = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { events += "user-start" },
                    onStartWithId = { _, _ ->
                        events += "recover"
                        events += "runtime-start"
                    },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val result =
                recovery.deliver(
                    delegate,
                    android.net.VpnService.SERVICE_INTERFACE,
                    3,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(listOf("recover", "runtime-start"), events)
        }

    @Test
    fun `dedicated recovery actions use recovery barrier`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)
            val recovery = fixture.recovery()

            val recoveredActions = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { error("recovery must not use the user-start path") },
                    onStartWithId = { _, _ -> recoveredActions += "recovered" },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            listOf(
                bootRecoveryStartAction,
                packageReplacedRecoveryStartAction,
                processDeathRecoveryStartAction,
            ).forEachIndexed { index, action ->
                assertEquals(
                    android.app.Service.START_STICKY,
                    recovery.deliver(
                        delegate,
                        action,
                        index + 10,
                    ),
                )
                runCurrent()
            }

            assertEquals(listOf("recovered", "recovered", "recovered"), recoveredActions)
        }

    @Test
    fun `unknown action is ignored without stopping service`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)

            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            val command = fixture.captureUnstamped()
            val result =
                command.deliver(
                    delegate,
                    "unknown",
                    9,
                )
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `onRevoke delegates to revoke handler`() =
        runTest {
            val fixture = ShellCommandFixture(Mode.VPN)

            var revokeCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = fixture.arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    onRevoke = { revokeCalls += 1 },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(fixture.authority::reference),
                )

            delegate.onRevoke()
            runCurrent()

            assertEquals(1, revokeCalls)
        }
}

private class RecordingServiceStopArtifactWriteStore(
    private val operations: MutableList<String>,
) : DiagnosticsArtifactWriteStore {
    var event: NativeSessionEventEntity? = null

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        operations += "persist"
        this.event = event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
}

/** Reservations and snapshots are immutable at dispatch; delivery never refreshes authority. */
private data class CapturedShellCommand(
    val processGeneration: Long?,
    val reference: PauseAuthorityRef?,
    val activation: RuntimeAppliedIntent?,
    val stopSnapshot: RuntimeAuthoritySnapshot?,
) {
    fun withoutProcessStamp() = copy(processGeneration = null)

    fun deliver(
        delegate: ServiceShellDelegate,
        action: String?,
        startId: Int,
        transportFailoverRequestId: Long? = null,
        transportFailoverTarget: TransportFailoverTarget? = null,
    ): Int =
        delegate.onStartCommand(
            action,
            startId,
            transportFailoverRequestId = transportFailoverRequestId,
            transportFailoverTarget = transportFailoverTarget,
            explicitUserIntentGeneration = processGeneration,
            durableReference = reference,
            activation = activation,
            stopSnapshot = stopSnapshot,
        )
}

private class ShellCommandFixture(
    private val mode: Mode,
) {
    val authority = testPauseAuthority()
    val arbiter = ServiceIntentArbiter(authority)

    fun reserveStart(): CapturedShellCommand {
        val receipt = authority.reserveStart(mode)
        val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
        return CapturedShellCommand(
            lease.processGeneration,
            receipt.authority,
            RuntimeAppliedIntent.Activation(receipt),
            null,
        )
    }

    fun reserveStop(): CapturedShellCommand {
        val receipt = authority.reserveStop()
        val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
        return CapturedShellCommand(lease.processGeneration, receipt.authority, null, authority.snapshotAuthority())
    }

    // Diagnostics owns a temporary teardown of the existing command, preserving its resume lease.
    fun captureDiagnosticsStop() =
        CapturedShellCommand(
            arbiter.captureExplicitUserIntentGeneration(),
            authority.reference(),
            null,
            authority.snapshotAuthority(),
        )

    fun captureUnstamped() = CapturedShellCommand(null, authority.reference(), null, null)

    /** Establish Running through the checked claim/ACK API, never by inventing an Applied phase. */
    fun seedRunning(): CapturedShellCommand {
        val command = reserveStart()
        val original = checkNotNull(command.activation)
        val identity = RuntimeAppliedUseIdentity(original.receipt.commandId, 1, mode.preferenceValue)
        check(authority.claimActivation(original.receipt, identity))
        check(
            authority.acknowledgeApplied(
                original,
                RuntimeAppliedUseReceipt(identity, emptyList(), 1_800_000_000_000L, 0, true, "0".repeat(64)),
            ),
        )
        return command
    }

    fun continuation(applied: CapturedShellCommand): CapturedShellCommand {
        val original = checkNotNull(applied.activation)
        val identity = RuntimeAppliedUseIdentity(original.receipt.commandId, 1, mode.preferenceValue)
        val acknowledged = checkNotNull(authority.acknowledgedAttempt(original, identity))
        return applied.copy(activation = RuntimeAppliedIntent.Continuation(original.receipt, acknowledged.identity))
    }

    fun recovery(): CapturedShellCommand {
        seedRunning()
        return captureRecovery()
    }

    fun captureRecovery(): CapturedShellCommand {
        val receipt = checkNotNull(authority.authorizeRecovery(mode, authority.snapshotAuthority()))
        val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
        return CapturedShellCommand(
            lease.processGeneration,
            receipt.authority,
            RuntimeAppliedIntent.Recovery(receipt, mode),
            null,
        )
    }
}

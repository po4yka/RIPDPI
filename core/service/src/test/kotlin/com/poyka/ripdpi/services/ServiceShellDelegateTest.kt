package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                startAction,
                1,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            assertEquals(listOf("prepare", "start"), operations)
        }

    @Test
    fun `transport failover restart preserves prepared fallback selection`() =
        runTest {
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 11L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            delegate.onStartCommand(
                startAction,
                2,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            assertEquals(listOf("fallback-restart", "prepare", "start"), operations)
        }

    @Test
    fun `transport failover without a target rejects the tracked request`() =
        runTest {
            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 13L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )
            delegate.onStartCommand(
                startAction,
                1,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 14L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { operations += "start" },
                    onStop = { _, _ -> },
                    beforeUserStart = { operations += "prepare" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                startupFallbackStartAction,
                1,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            assertEquals(listOf("start"), operations)
        }

    @Test
    fun `start commands forward their ids to protected runtime cleanup`() =
        runTest {
            val startIds = mutableListOf<Int>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId -> startIds += startId },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                startAction,
                7,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            delegate.onStartCommand(
                startupFallbackStartAction,
                8,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStartWithId = { _, startId ->
                        if (startId == 1) {
                            latestStartId = 2
                            delegate.onStartCommand(
                                startupFallbackStartAction,
                                latestStartId,
                                durableReference =
                                    com.poyka.ripdpi.data
                                        .PauseAuthorityRef(0),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            latestStartId = 1
            delegate.onStartCommand(
                startAction,
                latestStartId,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                startAction,
                1,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()
            delegate.onStartCommand(
                startAction,
                2,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                startupFallbackStartAction,
                1,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            delegate.onStartCommand(
                startAction,
                2,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val startResult =
                delegate.onStartCommand(
                    startAction,
                    1,
                    explicitUserIntentGeneration = 0L,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
            runCurrent()
            val stopResult =
                delegate.onStartCommand(
                    stopAction,
                    7,
                    explicitUserIntentGeneration = 0L,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
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
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    stopAction,
                    7,
                    explicitUserIntentGeneration = 0L,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    transportFailoverRestartAction,
                    8,
                    transportFailoverRequestId = 12L,
                    transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    isStopAllowed = { stopAllowed },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            stopAllowed = false
            val result =
                delegate.onStartCommand(
                    notificationStopAction,
                    9,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    notificationStopAction,
                    11,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
            runCurrent()

            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertEquals(listOf(11), stopIds)
        }

    @Test
    fun `diagnostics stop preserves its own resume lease`() =
        runTest {
            val tracker =
                RuntimeResumeIntentTracker(
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                )
            val lease = tracker.captureResumeLease()
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStop = { command ->
                                tracker.recordAcceptedStop()
                                (command as? AcceptedServiceStop.Prepared)?.reference
                                    ?: com.poyka.ripdpi.data
                                        .PauseAuthorityRef(0)
                            },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                diagnosticsStopAction,
                14,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            assertEquals(listOf(14), stopIds)
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun `diagnostics stop durably records its cause before runtime teardown`() =
        runTest {
            val operations = mutableListOf<String>()
            val store = RecordingServiceStopArtifactWriteStore(operations)
            val recorder = RoomServiceStopProvenanceRecorder(store, AndroidRuntimeEvidenceClock { 321L })
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, provenance ->
                        recorder.record(Mode.VPN, provenance)
                        operations += "stop"
                    },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            delegate.onStartCommand(
                diagnosticsStopAction,
                15,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
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
            val tracker =
                RuntimeResumeIntentTracker(
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                )
            val lease = tracker.captureResumeLease()
            tracker.withUserStart(action = {})
            val arbiter =
                ServiceIntentArbiter(
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                )
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
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
                                    ?: com.poyka.ripdpi.data
                                        .PauseAuthorityRef(0)
                            },
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            arbiter.userStop {}
            delegate.onStartCommand(
                stopAction,
                15,
                explicitUserIntentGeneration = arbiter.captureExplicitUserIntentGeneration(),
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            val newerStart = arbiter.userStart(arbiter::captureExplicitUserIntentGeneration) { true }
            delegate.onStartCommand(
                startAction,
                16,
                explicitUserIntentGeneration = newerStart,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            runCurrent()

            val ownership = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
            assertEquals(UserRuntimeIntent.Running, ownership.intent)
        }

    @Test
    fun `stale diagnostics compensation is skipped after accepted start`() =
        runTest {
            val tracker =
                RuntimeResumeIntentTracker(
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                )
            tracker.recordAcceptedStop()
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { startId, _ -> stopIds += startId },
                    intentCallbacks =
                        ServiceShellIntentCallbacks(
                            acceptedStart = tracker::recordAcceptedStart,
                            acceptedStop = { command ->
                                (command as? AcceptedServiceStop.Prepared)?.reference
                                    ?: com.poyka.ripdpi.data
                                        .PauseAuthorityRef(0)
                            },
                        ),
                    isCompensatingStopCurrent = tracker::isCurrentIntentStopped,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                startAction,
                17,
                explicitUserIntentGeneration = 0L,
                durableReference =
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
            )
            val result =
                delegate.onStartCommand(
                    diagnosticsCompensatingStopAction,
                    18,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
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
            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    null,
                    1,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    android.net.VpnService.SERVICE_INTERFACE,
                    2,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
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
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    android.net.VpnService.SERVICE_INTERFACE,
                    3,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { error("recovery must not use the user-start path") },
                    onStartWithId = { _, _ -> recoveredActions += "recovered" },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            listOf(
                bootRecoveryStartAction,
                packageReplacedRecoveryStartAction,
                processDeathRecoveryStartAction,
            ).forEachIndexed { index, action ->
                assertEquals(
                    android.app.Service.START_STICKY,
                    delegate.onStartCommand(
                        action,
                        index + 10,
                        durableReference =
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0),
                    ),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { startId, _ -> stopIds += startId },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
                )

            val result =
                delegate.onStartCommand(
                    "unknown",
                    9,
                    durableReference =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                )
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
                    serviceIntentArbiter =
                        ServiceIntentArbiter(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    onRevoke = { revokeCalls += 1 },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            com.poyka.ripdpi.data
                                .PauseAuthorityRef(0)
                        },
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

package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DesiredRuntimeState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.stopAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [ShadowServiceControllerVpnPrepareService::class])
class ServiceControllerForegroundDenialTest {
    private val authority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

    @Test
    fun validTransportFailoverIntentPreservesExactIdentity() =
        kotlinx.coroutines.test.runTest {
            val decoded =
                Intent(transportFailoverRestartAction)
                    .putExtra(transportFailoverRequestIdExtra, 17L)
                    .putExtra(transportFailoverTargetKindExtra, RelayKindVlessReality)
                    .putExtra(transportFailoverTargetProfileIdExtra, "reality-17")
                    .decodeTransportFailoverCommand()

            assertEquals(17L, decoded.requestId)
            assertEquals(
                TransportFailoverTarget(RelayKindVlessReality, "reality-17"),
                decoded.target,
            )
        }

    @Test
    fun malformedTransportFailoverIntentDecodesFailClosed() =
        kotlinx.coroutines.test.runTest {
            val decoded =
                Intent(transportFailoverRestartAction)
                    .putExtra(transportFailoverRequestIdExtra, "not-a-long")
                    .putExtra(transportFailoverTargetKindExtra, " ")
                    .putExtra(transportFailoverTargetProfileIdExtra, "")
                    .decodeTransportFailoverCommand()

            assertNull(decoded.requestId)
            assertNull(decoded.target)
        }

    @Test
    fun foregroundServiceDenialRejectsProxyStartWithoutReportingRunning() =
        kotlinx.coroutines.test.runTest {
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val starter =
                RecordingForegroundServiceStarter {
                    throw IllegalStateException("foreground start denied")
                }
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val result = controller.start(Mode.Proxy)

            assertTrue(result is ServiceStartResult.Rejected)
            assertTrue(
                (result as ServiceStartResult.Rejected).reason is ServiceStartRejectionReason.ForegroundServiceBlocked,
            )
            assertEquals(AppStatus.Halted to Mode.Proxy, serviceStateStore.status.value)
            assertEquals(1, starter.startCount)
            assertTrue(serviceStateStore.eventHistory.isEmpty())
            assertTrue(tracker.ownership(lease) is ResumeLeaseOwnership.Superseded)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU], shadows = [ShadowServiceControllerVpnPrepareService::class])
    fun missingNotificationsDoNotBlockForegroundIntent() =
        kotlinx.coroutines.test.runTest {
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val result = controller.start(Mode.Proxy)

            assertAccepted(authority, Mode.Proxy, result)
            assertEquals(1, starter.startCount)
            assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
        }

    @Test
    fun missingVpnConsentRejectsVpnStartBeforeForegroundIntent() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = Intent("shadow.vpn.permission")
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
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
    fun acceptedProxyStartIssuesForegroundIntent() =
        kotlinx.coroutines.test.runTest {
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val result = controller.start(Mode.Proxy)

            assertAccepted(authority, Mode.Proxy, result)
            assertEquals(1, starter.startCount)
            assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
        }

    @Test
    fun acceptedVpnStartIssuesForegroundIntent() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val result = controller.start(Mode.VPN)

            assertAccepted(authority, Mode.VPN, result)
            assertEquals(1, starter.startCount)
            assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
        }

    @Test
    fun doqSaveAndVpnDispatchCannotOverlapWhileStatusIsHalted() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val arbiter = ServiceIntentArbiter(authority)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.Proxy,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = arbiter,
                )

            val saveLease = checkNotNull(arbiter.tryReserveDoqSave { true })
            assertEquals(
                ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.DnsSettingsUpdatePending),
                controller.start(Mode.VPN),
            )
            assertEquals(0, starter.startCount)
            saveLease.close()

            assertAccepted(authority, Mode.VPN, controller.start(Mode.VPN))
            assertEquals(1, starter.startCount)
            assertNull(arbiter.tryReserveDoqSave { true })
            arbiter.completeVpnStart(arbiter.captureVpnStartGeneration())
            checkNotNull(arbiter.tryReserveDoqSave { true }).close()
        }

    @Test
    fun transportFailoverUsesInternalVpnRestartAction() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Running to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val target = TransportFailoverTarget(RelayKindVlessReality, "reality-1")
            val result =
                controller.restartVpnForTransportFailover(
                    requestId = 41L,
                    expectedTarget = target,
                    reference = controller.captureRuntimeSnapshot().reference,
                )

            assertEquals(ServiceStartResult.MaintenanceAccepted(Mode.VPN), result)
            assertEquals(transportFailoverRestartAction, starter.lastIntent?.action)
            assertEquals(41L, starter.lastIntent?.getLongExtra(transportFailoverRequestIdExtra, 0L))
            assertEquals(RelayKindVlessReality, starter.lastIntent?.getStringExtra(transportFailoverTargetKindExtra))
            assertEquals("reality-1", starter.lastIntent?.getStringExtra(transportFailoverTargetProfileIdExtra))
            assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun explicitTransportStartCarriesTargetAndSupersedesResumeLease() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = TestServiceStateStore(),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )
            val target = TransportFailoverTarget(TransportKindAmneziaWg, "awg-editor")
            assertAccepted(
                authority,
                Mode.VPN,
                controller.startVpnTransport(
                    42L,
                    target,
                    controller.prepareStart(Mode.VPN),
                ),
            )
            assertEquals(transportActivationStartAction, starter.lastIntent?.action)
            assertEquals(target, starter.lastIntent.decodeTransportFailoverCommand().target)
            assertEquals(42L, starter.lastIntent.decodeTransportFailoverCommand().requestId)
            assertFalse(tracker.ownership(lease) == ResumeLeaseOwnership.Owned)
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [ShadowServiceControllerVpnPrepareService::class])
class ServiceControllerRecoveryDispatchTest {
    private val authority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

    @Test
    fun startupFallbackUsesInternalVpnStartAction() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val original = authority.reserveStart(Mode.VPN)
            val fallbackLease = controller.captureStartupFallbackLease()
            val failedIdentity =
                com.poyka.ripdpi.data.RuntimeAppliedUseIdentity(
                    "failed-start",
                    1,
                    Mode.VPN.preferenceValue,
                )
            assertTrue(authority.claimActivation(original, failedIdentity))
            assertTrue(authority.terminateActivation(original, failedIdentity))
            val result = controller.startVpnForStartupFallback(fallbackLease)

            val dispatched = result as StartupFallbackDispatchResult.Dispatched
            val accepted = dispatched.startResult as ServiceStartResult.Accepted
            assertEquals(Mode.VPN, accepted.mode)
            assertEquals(original.authority, accepted.receipt.authority)
            assertEquals(
                com.poyka.ripdpi.data.RuntimeCommandOrigin
                    .StartupFallback(original.commandId),
                accepted.receipt.origin,
            )
            assertTrue(starter.lastIntent.capturedRuntimeActivation(authority, Mode.VPN) != null)
            assertEquals(startupFallbackStartAction, starter.lastIntent?.action)
            assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
            assertTrue(tracker.ownership(lease) is ResumeLeaseOwnership.Superseded)
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(tracker.captureResumeLease()))
        }

    @Test
    fun newerUserStartSupersedesCapturedStartupFallback() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )
            val fallbackLease = controller.captureStartupFallbackLease()

            assertAccepted(authority, Mode.VPN, controller.start(Mode.VPN))
            val result = controller.startVpnForStartupFallback(fallbackLease)

            assertEquals(StartupFallbackDispatchResult.Superseded, result)
            assertEquals(1, starter.startCount)
        }

    @Test
    fun newerUserStopSupersedesCapturedStartupFallback() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Running to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )
            val fallbackLease = controller.captureStartupFallbackLease()

            controller.stop()
            val result = controller.startVpnForStartupFallback(fallbackLease)

            assertEquals(StartupFallbackDispatchResult.Superseded, result)
            assertEquals(1, starter.startCount)
            assertEquals(stopAction, starter.lastIntent?.action)
        }

    @Test
    fun bootAndProcessDeathRecoveryUseDistinctInternalActions() =
        kotlinx.coroutines.test.runTest {
            testAppliedRuntimeCommand(authority, Mode.VPN)
            val reference = authority.snapshotAuthority()
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            assertAccepted(
                authority,
                Mode.VPN,
                controller.startForBootRecovery(Mode.VPN, Intent.ACTION_BOOT_COMPLETED, reference).also { result ->
                    testAcknowledgeRuntimeCommand(authority, (result as ServiceStartResult.Accepted).receipt)
                },
            )
            assertEquals(bootRecoveryStartAction, starter.lastIntent?.action)

            assertAccepted(
                authority,
                Mode.VPN,
                controller
                    .startForBootRecovery(
                        Mode.VPN,
                        Intent.ACTION_MY_PACKAGE_REPLACED,
                        authority.snapshotAuthority(),
                    ).also { result ->
                        testAcknowledgeRuntimeCommand(authority, (result as ServiceStartResult.Accepted).receipt)
                    },
            )
            assertEquals(packageReplacedRecoveryStartAction, starter.lastIntent?.action)

            assertAccepted(
                authority,
                Mode.VPN,
                controller.startForProcessDeathRecovery(Mode.VPN, authority.snapshotAuthority()),
            )
            assertEquals(processDeathRecoveryStartAction, starter.lastIntent?.action)
        }

    @Test
    fun explicitStopRequestPreservesRunningMarkerUntilServiceAcceptsIt() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = RecordingForegroundServiceStarter(),
                    bootSessionStateStore = store,
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()

            assertTrue(store.wasRunningAtUpdate())
            assertEquals(DesiredRuntimeState.Stopped, authority.snapshotAuthority().desired)
            assertFalse(authority.allowsRecovery(authority.reference(), Mode.Proxy))
        }

    @Test
    fun automationAcceptedStopClearsRunningMarkerWithoutServiceCallback() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Running to Mode.Proxy,
                        ),
                    serviceAutomationController =
                        Optional.of(
                            object : ServiceAutomationController {
                                override fun interceptStop(currentMode: Mode): Boolean = true
                            },
                        ),
                    foregroundServiceStarter = RecordingForegroundServiceStarter(),
                    bootSessionStateStore = store,
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()

            assertFalse(store.wasRunningAtUpdate())
        }

    @Test
    fun diagnosticsStopPreservesResumeIntentAndUpdateMarker() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = store,
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stopForDiagnostics(lease.durableAuthority)

            assertEquals(diagnosticsStopAction, starter.lastIntent?.action)
            assertTrue(store.wasRunningAtUpdate())
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }

    @Test
    fun diagnosticsStartUsesInternalActionWithoutReplacingUserIntent() =
        kotlinx.coroutines.test.runTest {
            testAppliedRuntimeCommand(authority, Mode.Proxy)
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            val result = controller.startForDiagnostics(Mode.Proxy, lease.durableAuthority)

            assertAccepted(authority, Mode.Proxy, result)
            assertEquals(diagnosticsStartAction, starter.lastIntent?.action)
            assertTrue(tracker.acceptOwnedRecovery(lease, (result as ServiceStartResult.Accepted).receipt))
            assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [ShadowServiceControllerVpnPrepareService::class])
class ServiceControllerIntentFencingTest {
    private val authority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

    @Test
    fun diagnosticsResumeDoesNotAcquireUserIntentArbiterWhileHoldingLease() =
        kotlinx.coroutines.test.runTest {
            testAppliedRuntimeCommand(authority, Mode.Proxy)
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val arbiter = ServiceIntentArbiter(authority)
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.Proxy,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = RecordingForegroundServiceStarter(),
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = arbiter,
                )
            val lockHeld = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val lockExecutor = Executors.newSingleThreadExecutor()
            val diagnosticsExecutor = Executors.newSingleThreadExecutor()
            val lockOwner =
                lockExecutor.submit {
                    arbiter.serialize {
                        lockHeld.countDown()
                        releaseLock.await(5, TimeUnit.SECONDS)
                    }
                }

            try {
                assertTrue(lockHeld.await(2, TimeUnit.SECONDS))
                val entered = CountDownLatch(1)
                val diagnosticsResume =
                    diagnosticsExecutor.submit<ServiceStartResult?> {
                        tracker.runIfOwned(lease) {
                            entered.countDown()
                            controller.startForDiagnostics(Mode.Proxy, lease.durableAuthority)
                        }
                    }

                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val progressExecutor = Executors.newSingleThreadExecutor()
                try {
                    progressExecutor.submit { tracker.recordAcceptedStop() }.get(2, TimeUnit.SECONDS)
                    assertTrue(tracker.ownership(lease) is ResumeLeaseOwnership.Superseded)
                    releaseLock.countDown()
                    assertAccepted(authority, Mode.Proxy, checkNotNull(diagnosticsResume.get(2, TimeUnit.SECONDS)))
                } finally {
                    progressExecutor.shutdownNow()
                }
            } finally {
                releaseLock.countDown()
                lockOwner.get(2, TimeUnit.SECONDS)
                lockExecutor.shutdownNow()
                diagnosticsExecutor.shutdownNow()
            }
        }

    @Test
    fun explicitStopRequestInvalidatesDurableResumeBeforeServiceAcceptance() =
        kotlinx.coroutines.test.runTest {
            val tracker = RuntimeResumeIntentTracker(authority)
            val lease = tracker.captureResumeLease()
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = RecordingForegroundServiceStarter(),
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = tracker,
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()

            assertTrue(tracker.ownership(lease) is ResumeLeaseOwnership.Superseded)
            assertEquals(DesiredRuntimeState.Stopped, authority.snapshotAuthority().desired)
        }

    @Test
    fun staleRecoveryReferenceCannotDispatchAfterUserStop() =
        kotlinx.coroutines.test.runTest {
            testAppliedRuntimeCommand(authority, Mode.Proxy)
            val reference = authority.snapshotAuthority()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                    pauseAuthority = authority,
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                )

            controller.stop()
            val result = controller.startForProcessDeathRecovery(Mode.Proxy, reference)

            assertEquals(ServiceStartResult.Rejected(Mode.Proxy, ServiceStartRejectionReason.Superseded), result)
            assertEquals(1, starter.startCount)
            assertEquals(stopAction, starter.lastIntent?.action)
            assertEquals(DesiredRuntimeState.Stopped, authority.snapshotAuthority().desired)
        }

    // T2 — stop routing: stop() reads the current mode from serviceStateStore and
    // dispatches the stop Intent to the matching service class, never the wrong one.

    @Test
    fun stopWhileProxyActiveRoutesStopToProxyService() =
        kotlinx.coroutines.test.runTest {
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()

            assertEquals(1, starter.startCount)
            assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
        }

    @Test
    fun stopWhileVpnActiveRoutesStopToVpnService() =
        kotlinx.coroutines.test.runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = RuntimeEnvironment.getApplication(),
                    serviceStateStore = serviceStateStore,
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()

            assertEquals(1, starter.startCount)
            assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
        }

    @Test
    fun stopThenLateRefreshOnlyStartsStopAndBroadcastsRefresh() =
        kotlinx.coroutines.test.runTest {
            val context: android.app.Application = RuntimeEnvironment.getApplication()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = context,
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Running to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.stop()
            controller.refreshHardKillSwitchState()

            assertEquals(1, starter.startCount)
            assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
            assertEquals(stopAction, starter.lastIntent?.action)
            val refreshBroadcasts =
                shadowOf(context).broadcastIntents.filter { it.action == hardKillSwitchRefreshBroadcastAction }
            assertEquals(1, refreshBroadcasts.size)
            assertEquals(context.packageName, refreshBroadcasts.single().`package`)
        }

    @Test
    fun haltedVpnDoesNotDispatchHardKillSwitchRefresh() =
        kotlinx.coroutines.test.runTest {
            val context: android.app.Application = RuntimeEnvironment.getApplication()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = context,
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Halted to Mode.VPN,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.refreshHardKillSwitchState()

            assertEquals(0, starter.startCount)
            assertTrue(
                shadowOf(context).broadcastIntents.none { it.action == hardKillSwitchRefreshBroadcastAction },
            )
        }

    @Test
    fun runningProxyDoesNotDispatchHardKillSwitchRefresh() =
        kotlinx.coroutines.test.runTest {
            val context: android.app.Application = RuntimeEnvironment.getApplication()
            val starter = RecordingForegroundServiceStarter()
            val controller =
                DefaultServiceController(
                    appSettings = TestAppSettingsRepository(),
                    profileRecovery =
                        com.poyka.ripdpi.data
                            .testProfileRecovery(),
                    pauseAuthority = authority,
                    context = context,
                    serviceStateStore =
                        TestServiceStateStore(
                            initialStatus =
                                AppStatus.Running to Mode.Proxy,
                        ),
                    serviceAutomationController = Optional.empty(),
                    foregroundServiceStarter = starter,
                    bootSessionStateStore = InMemoryBootSessionStateStore(),
                    runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                    serviceIntentArbiter = ServiceIntentArbiter(authority),
                )

            controller.refreshHardKillSwitchState()

            assertEquals(0, starter.startCount)
            assertTrue(
                shadowOf(context).broadcastIntents.none { it.action == hardKillSwitchRefreshBroadcastAction },
            )
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ServiceIntentGenerationTest {
    private val authority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

    @Test
    fun `dispatch carries generation already visible to delivery`() =
        kotlinx.coroutines.test.runTest {
            val arbiter = ServiceIntentArbiter(authority)
            val intents = mutableListOf<Intent>()
            val starter =
                object : ForegroundServiceStarter {
                    override fun startForegroundService(
                        context: Context,
                        intent: Intent,
                    ) {
                        assertEquals(
                            arbiter.captureExplicitUserIntentGeneration(),
                            intent.explicitUserIntentGeneration(),
                        )
                        intents += intent
                    }
                }
            val controller = controller(arbiter, starter)
            controller.start(Mode.Proxy)
            controller.start(Mode.Proxy)
            assertEquals(listOf(1L, 2L), intents.map { it.explicitUserIntentGeneration() })
        }

    @Test
    fun `rejected dispatch retains reserved generation and blocks stale recovery`() =
        kotlinx.coroutines.test.runTest {
            val arbiter = ServiceIntentArbiter(authority)
            val controller = controller(arbiter, RecordingForegroundServiceStarter { error("denied") })
            assertTrue(controller.start(Mode.Proxy) is ServiceStartResult.Rejected)
            assertEquals(1L, arbiter.captureExplicitUserIntentGeneration())
            assertEquals(1L, authority.reference().generation)
            assertNull(arbiter.recovery { "stale-recovery" })
        }

    private fun controller(
        arbiter: ServiceIntentArbiter,
        starter: ForegroundServiceStarter,
    ) = DefaultServiceController(
        appSettings = TestAppSettingsRepository(),
        profileRecovery =
            com.poyka.ripdpi.data
                .testProfileRecovery(),
        pauseAuthority = authority,
        context = RuntimeEnvironment.getApplication(),
        serviceStateStore =
            TestServiceStateStore(
                initialStatus =
                    AppStatus.Halted to Mode.Proxy,
            ),
        serviceAutomationController = Optional.empty(),
        foregroundServiceStarter = starter,
        bootSessionStateStore = InMemoryBootSessionStateStore(),
        runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
        serviceIntentArbiter = arbiter,
    )
}

internal class InMemoryBootSessionStateStore : BootSessionStateStore {
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

private fun assertAccepted(
    authority: com.poyka.ripdpi.data.PauseIntentAuthority,
    mode: Mode,
    result: ServiceStartResult,
) {
    val accepted = result as? ServiceStartResult.Accepted ?: error("Expected a typed accepted Start")
    assertEquals(mode, accepted.mode)
    assertTrue(authority.isCurrent(accepted.receipt))
}

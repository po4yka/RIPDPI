package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.poyka.ripdpi.data.AppStatus
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
    @Test
    fun validTransportFailoverIntentPreservesExactIdentity() {
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
    fun malformedTransportFailoverIntentDecodesFailClosed() {
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
    fun foregroundServiceDenialRejectsProxyStartWithoutReportingRunning() {
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
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
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        val result = controller.start(Mode.Proxy)

        assertTrue(result is ServiceStartResult.Rejected)
        assertTrue(
            (result as ServiceStartResult.Rejected).reason is ServiceStartRejectionReason.ForegroundServiceBlocked,
        )
        assertEquals(AppStatus.Halted to Mode.Proxy, serviceStateStore.status.value)
        assertEquals(1, starter.startCount)
        assertTrue(serviceStateStore.eventHistory.isEmpty())
        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
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
    fun transportFailoverUsesInternalVpnRestartAction() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        val target = TransportFailoverTarget(RelayKindVlessReality, "reality-1")
        val result = controller.restartVpnForTransportFailover(requestId = 41L, expectedTarget = target)

        assertEquals(ServiceStartResult.Accepted(Mode.VPN), result)
        assertEquals(transportFailoverRestartAction, starter.lastIntent?.action)
        assertEquals(41L, starter.lastIntent?.getLongExtra(transportFailoverRequestIdExtra, 0L))
        assertEquals(RelayKindVlessReality, starter.lastIntent?.getStringExtra(transportFailoverTargetKindExtra))
        assertEquals("reality-1", starter.lastIntent?.getStringExtra(transportFailoverTargetProfileIdExtra))
        assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun explicitTransportStartCarriesTargetAndSupersedesResumeLease() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )
        val target = TransportFailoverTarget(TransportKindAmneziaWg, "awg-editor")
        assertEquals(ServiceStartResult.Accepted(Mode.VPN), controller.startVpnTransport(42L, target))
        assertEquals(transportActivationStartAction, starter.lastIntent?.action)
        assertEquals(target, starter.lastIntent.decodeTransportFailoverCommand().target)
        assertEquals(42L, starter.lastIntent.decodeTransportFailoverCommand().requestId)
        assertFalse(tracker.ownership(lease) == ResumeLeaseOwnership.Owned)
    }

    @Test
    fun startupFallbackUsesInternalVpnStartAction() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        val fallbackLease = controller.captureStartupFallbackLease()
        val result = controller.startVpnForStartupFallback(fallbackLease)

        assertEquals(
            StartupFallbackDispatchResult.Dispatched(ServiceStartResult.Accepted(Mode.VPN)),
            result,
        )
        assertEquals(startupFallbackStartAction, starter.lastIntent?.action)
        assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun newerUserStartSupersedesCapturedStartupFallback() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )
        val fallbackLease = controller.captureStartupFallbackLease()

        assertEquals(ServiceStartResult.Accepted(Mode.VPN), controller.start(Mode.VPN))
        val result = controller.startVpnForStartupFallback(fallbackLease)

        assertEquals(StartupFallbackDispatchResult.Superseded, result)
        assertEquals(1, starter.startCount)
    }

    @Test
    fun newerUserStopSupersedesCapturedStartupFallback() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )
        val fallbackLease = controller.captureStartupFallbackLease()

        controller.stop()
        val result = controller.startVpnForStartupFallback(fallbackLease)

        assertEquals(StartupFallbackDispatchResult.Superseded, result)
        assertEquals(1, starter.startCount)
        assertEquals(stopAction, starter.lastIntent?.action)
    }

    @Test
    fun bootAndProcessDeathRecoveryUseDistinctInternalActions() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        assertEquals(
            ServiceStartResult.Accepted(Mode.VPN),
            controller.startForBootRecovery(Mode.VPN, Intent.ACTION_BOOT_COMPLETED),
        )
        assertEquals(bootRecoveryStartAction, starter.lastIntent?.action)

        assertEquals(
            ServiceStartResult.Accepted(Mode.VPN),
            controller.startForBootRecovery(Mode.VPN, Intent.ACTION_MY_PACKAGE_REPLACED),
        )
        assertEquals(packageReplacedRecoveryStartAction, starter.lastIntent?.action)

        assertEquals(ServiceStartResult.Accepted(Mode.VPN), controller.startForProcessDeathRecovery(Mode.VPN))
        assertEquals(processDeathRecoveryStartAction, starter.lastIntent?.action)
    }

    @Test
    fun explicitStopRequestPreservesRunningMarkerUntilServiceAcceptsIt() {
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

        assertTrue(store.wasRunningAtUpdate())
    }

    @Test
    fun automationAcceptedStopClearsRunningMarkerWithoutServiceCallback() {
        val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy),
                serviceAutomationController =
                    Optional.of(
                        object : ServiceAutomationController {
                            override fun interceptStop(currentMode: Mode): Boolean = true
                        },
                    ),
                foregroundServiceStarter = RecordingForegroundServiceStarter(),
                bootSessionStateStore = store,
            )

        controller.stop()

        assertFalse(store.wasRunningAtUpdate())
    }

    @Test
    fun diagnosticsStopPreservesResumeIntentAndUpdateMarker() {
        val store = InMemoryBootSessionStateStore().apply { setWasRunningAtUpdate(true) }
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = store,
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        controller.stopForDiagnostics()

        assertEquals(diagnosticsStopAction, starter.lastIntent?.action)
        assertTrue(store.wasRunningAtUpdate())
        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun diagnosticsStartUsesInternalActionWithoutReplacingUserIntent() {
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        val result = controller.startForDiagnostics(Mode.Proxy)

        assertEquals(ServiceStartResult.Accepted(Mode.Proxy), result)
        assertEquals(diagnosticsStartAction, starter.lastIntent?.action)
        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun diagnosticsResumeDoesNotAcquireUserIntentArbiterWhileHoldingLease() {
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val arbiter = ServiceIntentArbiter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy),
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
            val diagnosticsResume =
                diagnosticsExecutor.submit<ServiceStartResult?> {
                    tracker.runIfOwned(lease) {
                        controller.startForDiagnostics(Mode.Proxy)
                    }
                }

            assertEquals(ServiceStartResult.Accepted(Mode.Proxy), diagnosticsResume.get(2, TimeUnit.SECONDS))
        } finally {
            releaseLock.countDown()
            lockOwner.get(2, TimeUnit.SECONDS)
            lockExecutor.shutdownNow()
            diagnosticsExecutor.shutdownNow()
        }
    }

    @Test
    fun explicitStopRequestWaitsForServiceAcceptanceBeforeInvalidatingResume() {
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = RecordingForegroundServiceStarter(),
                bootSessionStateStore = InMemoryBootSessionStateStore(),
                runtimeResumeIntentTracker = tracker,
                serviceIntentArbiter = ServiceIntentArbiter(),
            )

        controller.stop()

        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    // T2 — stop routing: stop() reads the current mode from serviceStateStore and
    // dispatches the stop Intent to the matching service class, never the wrong one.
    @Test
    fun stopWhileProxyActiveRoutesStopToProxyService() {
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        controller.stop()

        assertEquals(1, starter.startCount)
        assertEquals(RipDpiProxyService::class.java.name, starter.lastIntent?.component?.className)
    }

    @Test
    fun stopWhileVpnActiveRoutesStopToVpnService() {
        ShadowServiceControllerVpnPrepareService.prepareIntent = null
        val serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = serviceStateStore,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        controller.stop()

        assertEquals(1, starter.startCount)
        assertEquals(RipDpiVpnService::class.java.name, starter.lastIntent?.component?.className)
    }

    @Test
    fun stopThenLateRefreshOnlyStartsStopAndBroadcastsRefresh() {
        val context: android.app.Application = RuntimeEnvironment.getApplication()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = context,
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
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
    fun haltedVpnDoesNotDispatchHardKillSwitchRefresh() {
        val context: android.app.Application = RuntimeEnvironment.getApplication()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = context,
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.VPN),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
            )

        controller.refreshHardKillSwitchState()

        assertEquals(0, starter.startCount)
        assertTrue(
            shadowOf(context).broadcastIntents.none { it.action == hardKillSwitchRefreshBroadcastAction },
        )
    }

    @Test
    fun runningProxyDoesNotDispatchHardKillSwitchRefresh() {
        val context: android.app.Application = RuntimeEnvironment.getApplication()
        val starter = RecordingForegroundServiceStarter()
        val controller =
            DefaultServiceController(
                context = context,
                serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy),
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = InMemoryBootSessionStateStore(),
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
    @Test
    fun `dispatch carries generation already visible to delivery`() {
        val arbiter = ServiceIntentArbiter()
        val intents = mutableListOf<Intent>()
        val starter =
            object : ForegroundServiceStarter {
                override fun startForegroundService(
                    context: Context,
                    intent: Intent,
                ) {
                    assertEquals(arbiter.captureExplicitUserIntentGeneration(), intent.explicitUserIntentGeneration())
                    intents += intent
                }
            }
        val controller = controller(arbiter, starter)
        controller.start(Mode.Proxy)
        controller.start(Mode.Proxy)
        assertEquals(listOf(1L, 2L), intents.map { it.explicitUserIntentGeneration() })
    }

    @Test
    fun `rejected dispatch restores previous generation and recovery authority`() {
        val arbiter = ServiceIntentArbiter()
        val controller = controller(arbiter, RecordingForegroundServiceStarter { error("denied") })
        assertTrue(controller.start(Mode.Proxy) is ServiceStartResult.Rejected)
        assertEquals(0L, arbiter.captureExplicitUserIntentGeneration())
        assertEquals("still-owned", arbiter.recovery { "still-owned" })
    }

    private fun controller(
        arbiter: ServiceIntentArbiter,
        starter: ForegroundServiceStarter,
    ) = DefaultServiceController(
        context = RuntimeEnvironment.getApplication(),
        serviceStateStore = TestServiceStateStore(initialStatus = AppStatus.Halted to Mode.Proxy),
        serviceAutomationController = Optional.empty(),
        foregroundServiceStarter = starter,
        bootSessionStateStore = InMemoryBootSessionStateStore(),
        runtimeResumeIntentTracker = RuntimeResumeIntentTracker(),
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

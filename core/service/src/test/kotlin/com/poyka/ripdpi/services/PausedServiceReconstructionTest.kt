package com.poyka.ripdpi.services

import android.Manifest
import android.content.Intent
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import dagger.hilt.internal.GeneratedComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/** Executes the real Android service lifecycle with active-session factories forbidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PausedServiceReconstructionTest {
    @Test
    fun `proxy paused recovery creates no active session native root or selector graph`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            skipPausedServiceInjection(service)
            service.serviceStateStore = fixture.state
            service.pauseAuthority = fixture.authority
            service.pauseController = fixture.pause
            service.profileRecovery = fixture.recovery
            service.runtimeEvidenceReporter = fixture.reporter
            service.sessionComponentBuilderProvider = fixture.forbidden("proxy active session")
            service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors and probes")
            service.rootHelperManagerProvider = fixture.forbidden("native root helper")
            lifecycle.create()
            assertEquals(0, fixture.constructions.get())
            service.onStartCommand(null, 0, 1)
            withTimeout(5_000) { fixture.authority.states.first { it?.pause?.phase == PausePhase.Deferred } }
            assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
            assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
            lifecycle.destroy()
            awaitPausedServiceDestruction(service)
            service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `vpn paused recovery creates no JNI TUN protect native or selector session graph`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            skipPausedServiceInjection(service)
            service.serviceStateStore = fixture.state
            service.pauseAuthority = fixture.authority
            service.pauseController = fixture.pause
            service.profileMutationCoordinator = fixture.recovery
            service.runtimeEvidenceReporter = fixture.reporter
            service.liveVpnLockdownReader = LiveVpnLockdownReader()
            service.hardKillSwitchStateStore =
                SharedPreferencesAndroidHardKillSwitchStateStore(RuntimeEnvironment.getApplication())
            service.recoveryReceiptCollector =
                RemoteDeviceRecoveryReceiptCollector(
                    SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(RuntimeEnvironment.getApplication()),
                )
            service.activeProtectSocketPathProvider = ActiveProtectSocketPathProvider()
            service.sessionComponentBuilderProvider = fixture.forbidden("VPN JNI TUN protect native session")
            service.selectorRuntimeLifecycleListenersProvider = fixture.forbidden("selectors and probes")
            service.rootHelperManagerProvider = fixture.forbidden("native root helper")
            lifecycle.create()
            assertEquals(0, fixture.constructions.get())
            service.onStartCommand(null, 0, 1)
            withTimeout(5_000) { fixture.authority.states.first { it?.pause?.phase == PausePhase.Deferred } }
            assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
            assertNull(service.activeProtectSocketPathProvider.current())
            lifecycle.destroy()
            awaitPausedServiceDestruction(service)
            service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `vpn attach waits for the previous service instance teardown`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            val factoryCalls = AtomicInteger()
            service.sessionComponentBuilderProvider =
                Provider {
                    factoryCalls.incrementAndGet()
                    error("graph is not needed for this test")
                }
            lifecycle.create()
            val previousTeardown = Job()
            RipDpiVpnService.previousInstanceTeardown.set(previousTeardown)
            try {
                val attach = async(Dispatchers.Default) { runCatching { attachPausedServiceSession(service) } }
                delay(200)
                assertEquals("attach must wait until the old instance released its singletons", 0, factoryCalls.get())

                previousTeardown.complete()
                attach.await()

                assertEquals(1, factoryCalls.get())
            } finally {
                previousTeardown.complete()
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `revoke requests destruction and retains its failure reason through pending cleanup`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            val controller = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = controller.get()
            initializePausedVpnService(service, fixture)
            val store = DefaultServiceStateStore()
            service.serviceStateStore = store
            controller.create()
            val initializer = ServiceSessionStateInitializer(store)
            val session = initializer.initialize(Mode.VPN)
            session.setStatus(AppStatus.Running, Mode.VPN)
            val sessionLifecycle =
                VpnServiceSessionLifecycle(
                    service,
                    service.sessionComponentBuilderProvider,
                    service.activeProtectSocketPathProvider,
                    service.runtimeResumeIntentTracker,
                    service.serviceIntentArbiter,
                    service.acceptedUserStopRecorder,
                    service.transportFailoverApplyTracker,
                    service.serviceStopProvenanceRecorder,
                )
            ReflectionHelpers.setField(service, "sessionLifecycle", sessionLifecycle)
            ReflectionHelpers.setField(sessionLifecycle, "stateInitializer", initializer)
            ReflectionHelpers.setField(sessionLifecycle, "sessionStateStore", session)
            val protection = service.activeProtectSocketPathProvider
            val path = "/tmp/revoked-vpn-protection.sock"
            ReflectionHelpers.setField(sessionLifecycle, "protectPathLease", protection.set(path) { true })
            val attempted = CompletableDeferred<Unit>()
            val allowRelease = CompletableDeferred<Unit>()
            val ownership = ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership")
            var destroyed = false
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { runtime ->
                ownership.own {
                    attempted.complete(Unit)
                    if (!allowRelease.isCompleted) throw RuntimeCleanupPendingException()
                    runtime.close()
                    sessionLifecycle.destroy()
                }
                try {
                    service.onRevoke()
                    assertTrue(shadowOf(service).isStoppedBySelf)
                    assertEquals(AppStatus.Running to Mode.VPN, store.status.value)
                    destroyed = true
                    controller.destroy()
                    withTimeout(5_000) { attempted.await() }
                    val teardown = checkNotNull(ReflectionHelpers.getField<Job?>(service, "destroyCleanupJob"))
                    assertFalse(teardown.isCompleted)
                    assertFalse(runtime.isClosed)
                    assertEquals(path, protection.current())

                    allowRelease.complete(Unit)
                    awaitPausedServiceDestruction(service)
                    assertTrue(runtime.isClosed)
                    assertNull(protection.current())
                    val failure = withTimeout(1_000) { store.events.first() } as ServiceEvent.Failed
                    assertEquals(FailureReason.PermissionLost("VPN"), failure.reason)
                    assertEquals(Sender.VPN, failure.sender)
                } finally {
                    allowRelease.complete(Unit)
                    if (!destroyed) controller.destroy()
                    awaitPausedServiceDestruction(service)
                    service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        }

    @Test
    fun `vpn destruction retains and retries cleanup before a replacement can attach`() =
        runBlocking {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val oldLifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val old = oldLifecycle.get()
            initializePausedVpnService(old, PausedServiceReconstructionFixture(Mode.VPN))
            oldLifecycle.create()
            val replacementLifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val replacement = replacementLifecycle.get()
            initializePausedVpnService(replacement, PausedServiceReconstructionFixture(Mode.VPN))
            val graphFailure = IllegalStateException("replacement graph is not needed")
            val constructions = AtomicInteger()
            replacement.sessionComponentBuilderProvider =
                Provider {
                    constructions.incrementAndGet()
                    throw graphFailure
                }
            replacementLifecycle.create()
            val attempted = CompletableDeferred<Unit>()
            val allowRelease = CompletableDeferred<Unit>()
            val ownership = ReflectionHelpers.getField<ActiveSessionOwnership>(old, "activeOwnership")
            val defaultTimeout = RipDpiVpnService.previousInstanceTeardownTimeoutMillis
            RipDpiVpnService.previousInstanceTeardownTimeoutMillis = 100L
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { runtime ->
                ownership.own {
                    attempted.complete(Unit)
                    if (!allowRelease.isCompleted) throw RuntimeCleanupPendingException()
                    runtime.close()
                }
                try {
                    oldLifecycle.destroy()
                    withTimeout(5_000) { attempted.await() }
                    val teardown = checkNotNull(ReflectionHelpers.getField<Job?>(old, "destroyCleanupJob"))
                    assertFalse(teardown.isCompleted)
                    assertFalse(runtime.isClosed)
                    val blocked = runCatching { attachPausedServiceSession(replacement) }.exceptionOrNull()
                    assertTrue(blocked is RuntimeCleanupPendingException)
                    assertEquals(0, constructions.get())

                    allowRelease.complete(Unit)
                    awaitPausedServiceDestruction(old)
                    assertTrue(runtime.isClosed)
                    assertFalse(ownership.hasOwnership)
                    assertSame(graphFailure, runCatching { attachPausedServiceSession(replacement) }.exceptionOrNull())
                    assertEquals(1, constructions.get())
                } finally {
                    allowRelease.complete(Unit)
                    RipDpiVpnService.previousInstanceTeardownTimeoutMillis = defaultTimeout
                    awaitPausedServiceDestruction(old)
                    replacementLifecycle.destroy()
                    awaitPausedServiceDestruction(replacement)
                    old.serviceScope.coroutineContext[Job]?.cancelAndJoin()
                    replacement.serviceScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        }

    @Test
    fun `vpn teardown finishes only after the earlier instance teardown`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            lifecycle.create()
            val earlierTeardown = Job()
            val defaultTimeout = RipDpiVpnService.previousInstanceTeardownTimeoutMillis
            RipDpiVpnService.previousInstanceTeardownTimeoutMillis = 100L
            RipDpiVpnService.previousInstanceTeardown.set(earlierTeardown)
            try {
                lifecycle.destroy()
                val teardown = checkNotNull(ReflectionHelpers.getField<Job?>(service, "destroyCleanupJob"))
                delay(200)
                assertFalse("the published job must cover the earlier teardown", teardown.isCompleted)

                earlierTeardown.complete()
                awaitPausedServiceDestruction(service)
            } finally {
                RipDpiVpnService.previousInstanceTeardownTimeoutMillis = defaultTimeout
                earlierTeardown.complete()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `vpn attach rejects unfinished teardown and can retry after it completes`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            val graphFailure = IllegalStateException("graph is not needed for this test")
            service.sessionComponentBuilderProvider = Provider { throw graphFailure }
            lifecycle.create()
            val hungTeardown = Job()
            val defaultTimeout = RipDpiVpnService.previousInstanceTeardownTimeoutMillis
            RipDpiVpnService.previousInstanceTeardownTimeoutMillis = 100L
            RipDpiVpnService.previousInstanceTeardown.set(hungTeardown)
            try {
                val failure =
                    withTimeout(5_000) { runCatching { attachPausedServiceSession(service) }.exceptionOrNull() }

                assertTrue("a pending predecessor must reject acquisition", failure is RuntimeCleanupPendingException)
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)

                hungTeardown.complete()
                assertSame(graphFailure, runCatching { attachPausedServiceSession(service) }.exceptionOrNull())
            } finally {
                RipDpiVpnService.previousInstanceTeardownTimeoutMillis = defaultTimeout
                hungTeardown.complete()
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `vpn rejected predecessor wait completes the start generation and stops the idle service`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            lifecycle.create()
            val hungTeardown = Job()
            val defaultTimeout = RipDpiVpnService.previousInstanceTeardownTimeoutMillis
            RipDpiVpnService.previousInstanceTeardownTimeoutMillis = 100L
            RipDpiVpnService.previousInstanceTeardown.set(hungTeardown)
            try {
                val receipt = fixture.authority.reserveStart(Mode.VPN)
                val lease = checkNotNull(fixture.arbiter.dispatchExplicit(receipt))
                fixture.arbiter.dispatchVpnStart { ServiceStartResult.Accepted(receipt) }
                val start =
                    Intent(RuntimeEnvironment.getApplication(), RipDpiVpnService::class.java).apply {
                        action = startAction
                        stampRuntimeActivation(receipt)
                        putExtra(explicitUserIntentGenerationExtra, lease.processGeneration)
                        putExtra(durableIntentGenerationExtra, receipt.authority.generation)
                        putExtra(vpnStartGenerationExtra, fixture.arbiter.captureVpnStartGeneration())
                    }

                service.onStartCommand(start, 0, 7)

                withTimeout(5_000) {
                    while (!shadowOf(service).isStoppedBySelf) delay(10)
                }
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                checkNotNull(fixture.arbiter.tryReserveDoqSave { true }).close()
            } finally {
                RipDpiVpnService.previousInstanceTeardownTimeoutMillis = defaultTimeout
                hungTeardown.complete()
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `vpn start dropped before the shell still completes its start generation`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            lifecycle.create()
            try {
                fixture.arbiter.dispatchVpnStart {
                    ServiceStartResult.Accepted(fixture.authority.reserveStart(Mode.VPN))
                }
                assertNull("the accepted start blocks a DoQ save", fixture.arbiter.tryReserveDoqSave { true })
                // No current durable reference: a newer command superseded this start before it ran.
                val supersededStart =
                    Intent(RuntimeEnvironment.getApplication(), RipDpiVpnService::class.java)
                        .setAction(startAction)
                        .putExtra(vpnStartGenerationExtra, fixture.arbiter.captureVpnStartGeneration())

                service.onStartCommand(supersededStart, 0, 1)

                withTimeout(5_000) {
                    while (true) {
                        val lease = fixture.arbiter.tryReserveDoqSave { true }
                        if (lease != null) {
                            lease.close()
                            break
                        }
                        delay(10)
                    }
                }
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `proxy throwing graph factory releases attempted ownership without accessing unrelated providers`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializePausedProxyService(service, fixture)
            val original = IllegalStateException("graph factory failed")
            service.sessionComponentBuilderProvider = Provider { throw original }
            lifecycle.create()
            try {
                assertSame(original, runCatching { attachPausedServiceSession(service) }.exceptionOrNull())
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(0, fixture.constructions.get())
        }

    @Test
    fun `proxy runtime constructor failure closes the real initialized state before clearing references`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializePausedProxyService(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val original = IllegalStateException("runtime constructor failed")
            val component =
                object : ProxyServiceSessionComponent, ProxyServiceSessionEntryPoint, GeneratedComponent {
                    override fun stateInitializer() = initializer

                    override fun coordinator(): ProxyServiceRuntimeCoordinator {
                        assertSame(fixture.state, initializer.requireInitialized())
                        throw original
                    }
                }
            service.sessionComponentBuilderProvider =
                Provider {
                    object : ProxyServiceSessionComponentBuilder {
                        override fun host(host: ServiceCoordinatorHost): ProxyServiceSessionComponentBuilder {
                            assertSame(service, host)
                            return this
                        }

                        override fun build(): ProxyServiceSessionComponent = component
                    }
                }
            lifecycle.create()
            try {
                assertSame(original, runCatching { attachPausedServiceSession(service) }.exceptionOrNull())
                assertTrue(runCatching { initializer.requireInitialized() }.exceptionOrNull() is IllegalStateException)
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "stateInitializer"))
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `vpn throwing graph factory unregisters already started underlay and route callbacks`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.VPN)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
            val lifecycle = Robolectric.buildService(RipDpiVpnService::class.java)
            val service = lifecycle.get()
            initializePausedVpnService(service, fixture)
            val original = IllegalStateException("VPN graph factory failed")
            val routes = service.vpnRouteObservationAuthority
            var factoryCalls = 0
            service.sessionComponentBuilderProvider =
                Provider {
                    factoryCalls += 1
                    assertNotNull(ReflectionHelpers.getField<Any?>(service.underlyingNetworkBinder, "callback"))
                    assertNotNull(ReflectionHelpers.getField<Any?>(routes, "registeredCallback"))
                    throw original
                }
            lifecycle.create()
            try {
                assertSame(original, runCatching { attachPausedServiceSession(service) }.exceptionOrNull())
                assertEquals(1, factoryCalls)
                assertNull(ReflectionHelpers.getField<Any?>(service.underlyingNetworkBinder, "callback"))
                assertNull(ReflectionHelpers.getField<Any?>(routes, "registeredCallback"))
                assertNull(service.activeProtectSocketPathProvider.current())
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertEquals(0, fixture.constructions.get())
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `throwing selector startup releases earlier and partially started listeners plus real session graph`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializePausedProxyService(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val runtime = pausedProxyCoordinator(service, fixture)
            service.sessionComponentBuilderProvider = pausedProxyComponentProvider(service, initializer, runtime)
            val events = mutableListOf<String>()
            val first = ReconstructionFaultListener("first", events)
            val second = ReconstructionFaultListener("second", events, failStart = true)
            val reads = AtomicInteger()
            service.selectorRuntimeLifecycleListenersProvider =
                Provider {
                    reads.incrementAndGet()
                    linkedSetOf(first, second)
                }
            lifecycle.create()
            try {
                val failure = runCatching { attachPausedServiceSession(service) }.exceptionOrNull()
                assertSame(second.startFailure, failure)
                assertEquals(listOf("start first", "start second", "stop second", "stop first"), events)
                assertTrue(runCatching { initializer.requireInitialized() }.exceptionOrNull() is IllegalStateException)
                assertFalse(ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "shellDelegate"))
                assertEquals(1, reads.get())
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(1, reads.get())
        }

    @Test
    fun `failed listener rollback retains foreground ownership and Stop retries without losing handles`() =
        runBlocking {
            val fixture = PausedServiceReconstructionFixture(Mode.Proxy)
            val lifecycle = Robolectric.buildService(RipDpiProxyService::class.java)
            val service = lifecycle.get()
            initializePausedProxyService(service, fixture)
            val initializer = ServiceSessionStateInitializer(fixture.state)
            val runtime = pausedProxyCoordinator(service, fixture)
            service.sessionComponentBuilderProvider = pausedProxyComponentProvider(service, initializer, runtime)
            val events = mutableListOf<String>()
            val first = ReconstructionFaultListener("first", events)
            val second = ReconstructionFaultListener("second", events, failStart = true, failStop = true)
            val reads = AtomicInteger()
            service.selectorRuntimeLifecycleListenersProvider =
                Provider {
                    reads.incrementAndGet()
                    linkedSetOf(first, second)
                }
            lifecycle.create()
            try {
                val failure = runCatching { attachPausedServiceSession(service) }.exceptionOrNull()
                assertTrue(failure is ActiveSessionCleanupPendingException)
                assertSame(second.startFailure, failure?.cause)
                val ownership = ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership")
                assertTrue(ownership.hasOwnership)
                assertTrue(ownership.cleanupPending)
                assertEquals(
                    PausePhase.CleanupPending,
                    fixture.pause.cleanupPending.value
                        ?.phase,
                )
                assertNotNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertEquals(1, first.stops)
                second.failStop = false

                assertEquals(RuntimeStopOutcome.FullyReleased, fixture.pause.stop())
                assertEquals(2, second.stops)
                assertEquals(1, first.stops)
                assertFalse(ownership.hasOwnership)
                assertNull(ReflectionHelpers.getField<Any?>(service, "sessionComponent"))
                assertNull(ReflectionHelpers.getField<Any?>(service, "coordinator"))
                assertEquals(1, reads.get())
            } finally {
                lifecycle.destroy()
                awaitPausedServiceDestruction(service)
                service.serviceScope.coroutineContext[Job]?.cancelAndJoin()
            }
            assertEquals(1, reads.get())
        }
}

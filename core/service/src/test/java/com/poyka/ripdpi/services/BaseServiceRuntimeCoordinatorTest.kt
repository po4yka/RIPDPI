@file:Suppress("MaxLineLength", "UnusedParameter", "UseCheckOrError")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BaseServiceRuntimeCoordinatorTest {
    @Test
    fun duplicateStartIsIgnoredUntilStopCompletes() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            env.coordinator.start()
            runCurrent()

            assertEquals(1, env.coordinator.startCalls)
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun failedStartReturnsLifecycleToStoppedAndAllowsRetry() =
        runTest {
            val env = newEnv().also { it.coordinator.failOnStart = true }

            env.coordinator.start()
            runCurrent()

            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(listOf(ServiceStatus.Failed, ServiceStatus.Disconnected), env.coordinator.statusTransitions)

            env.coordinator.failOnStart = false
            env.coordinator.start()
            runCurrent()

            assertEquals(2, env.coordinator.startCalls)
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun stopFinalizationUnregistersRuntimeAndRequestsStopSelfOnce() =
        runTest {
            val env = newEnv()
            val stopGate = CompletableDeferred<Unit>()
            env.coordinator.stopGate = stopGate

            env.coordinator.start()
            runCurrent()
            backgroundScope.launch { env.coordinator.stop(stopSelfStartId = 7) }
            runCurrent()
            backgroundScope.launch { env.coordinator.stop(stopSelfStartId = 8) }
            runCurrent()
            stopGate.complete(Unit)
            runCurrent()

            assertEquals(1, env.coordinator.stopCalls)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(listOf(7), env.host.stopRequests)
        }

    @Test
    fun nonActionableAndCooldownHandoverEventsAreIgnored() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env = newEnv(fingerprint = initialFingerprint)

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = null,
                    classification = "connectivity_loss",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            assertEquals(0, env.coordinator.restartCalls)

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            assertEquals(1, env.coordinator.restartCalls)
            assertEquals(1, env.handoverEvents.published.size)

            env.clock.now += 1_000L
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            assertEquals(1, env.coordinator.restartCalls)
            assertEquals(1, env.handoverEvents.published.size)
        }

    private fun TestScope.newEnv(fingerprint: NetworkFingerprint? = sampleFingerprint()): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = TestProxyServiceHost(backgroundScope)
        val resolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.Proxy))
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val handoverEvents = TestPolicyHandoverEventStore()
        val clock = TestServiceClock(now = 1_000L)
        val coordinator =
            TestCoordinator(
                host = host,
                resolver = resolver,
                runtimeRegistry = runtimeRegistry,
                rememberedStore = TestRememberedNetworkPolicyStore(),
                handoverMonitor = handoverMonitor,
                handoverEvents = handoverEvents,
                dispatcher = dispatcher,
                clock = clock,
            )
        return Env(
            coordinator = coordinator,
            host = host,
            runtimeRegistry = runtimeRegistry,
            handoverMonitor = handoverMonitor,
            handoverEvents = handoverEvents,
            clock = clock,
        )
    }

    private data class Env(
        val coordinator: TestCoordinator,
        val host: TestProxyServiceHost,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val handoverEvents: TestPolicyHandoverEventStore,
        val clock: TestServiceClock,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TestCoordinator(
    host: ServiceCoordinatorHost,
    resolver: ConnectionPolicyResolver,
    runtimeRegistry: ServiceRuntimeRegistry,
    rememberedStore: TestRememberedNetworkPolicyStore,
    handoverMonitor: TestNetworkHandoverMonitor,
    handoverEvents: TestPolicyHandoverEventStore,
    dispatcher: kotlinx.coroutines.test.TestDispatcher,
    clock: TestServiceClock,
) : BaseServiceRuntimeCoordinator<ProxyRuntimeSession>(
        mode = Mode.Proxy,
        host = host,
        connectionPolicyResolver = resolver,
        serviceRuntimeRegistry = runtimeRegistry,
        rememberedNetworkPolicyStore = rememberedStore,
        networkHandoverMonitor = handoverMonitor,
        policyHandoverEventStore = handoverEvents,
        ioDispatcher = dispatcher,
        clock = clock,
    ) {
    var failOnStart: Boolean = false
    var stopGate: CompletableDeferred<Unit>? = null
    var startCalls: Int = 0
    var stopCalls: Int = 0
    var restartCalls: Int = 0
    val statusTransitions = mutableListOf<ServiceStatus>()

    override val serviceLabel: String = "test"

    override fun createRuntimeSession(): ProxyRuntimeSession = ProxyRuntimeSession()

    override suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        sampleResolution(mode = Mode.Proxy)

    override suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution = sampleResolution(mode = Mode.Proxy, policySignature = "handover")

    override fun applyActiveConnectionPolicy(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy = resolution.appliedPolicy ?: return
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.Proxy,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    override suspend fun startResolvedRuntime(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        startCalls += 1
        if (failOnStart) {
            throw IllegalStateException("boom")
        }
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        stopCalls += 1
        stopGate?.await()
    }

    override fun startModeTelemetryUpdates() = Unit

    override suspend fun restartAfterHandover(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        restartCalls += 1
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "handover",
            appliedAt = appliedAt,
        )
    }

    override fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        status = newStatus
        statusTransitions += newStatus
    }

    override fun classifyStartupFailure(error: Exception): FailureReason = FailureReason.Unexpected(error)

    override fun classifyHandoverFailure(error: Exception): FailureReason = FailureReason.Unexpected(error)
}

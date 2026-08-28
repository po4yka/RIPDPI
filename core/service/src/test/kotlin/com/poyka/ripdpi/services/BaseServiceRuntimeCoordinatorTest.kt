package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun `start publishes runtime evidence before connected status`() =
        runTest {
            val env = newEnv()
            env.coordinator.readySnapshot =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:18083",
                    autolearnEnabled = true,
                    capturedAt = 1787231291791410L,
                )

            env.coordinator.start()
            runCurrent()

            val runtimeId = checkNotNull(env.coordinator.publishedRuntimeId)
            assertEquals(
                listOf(
                    "runtime_start",
                    "publish_evidence:$runtimeId",
                    "status:Connected",
                ),
                env.coordinator.startLifecycleEvents,
            )
            val evidence = env.coordinator.publishedEvidence as RuntimeStartEvidence.ProxySnapshot
            assertSame(env.coordinator.readySnapshot, evidence.snapshot)
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
    fun failedStartStopsOnlyItsOriginatingServiceStart() =
        runTest {
            val env = newEnv().also { it.coordinator.failOnStart = true }

            env.coordinator.start(stopSelfStartId = 41)
            runCurrent()

            assertEquals(listOf(41), env.host.stopRequests)
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
    fun runtimeStopFailureStillFinalizesAndStopsService() =
        runTest {
            val env = newEnv().also { it.coordinator.failOnStop = true }
            env.coordinator.start()
            runCurrent()

            env.coordinator.stop(stopSelfStartId = 7)

            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(listOf(7), env.host.stopRequests)
            assertEquals(listOf(ServiceStatus.Connected, ServiceStatus.Disconnected), env.coordinator.statusTransitions)
        }

    @Test
    fun finalTelemetryIsCapturedBeforeRuntimeStop() =
        runTest {
            val env = newEnv()
            env.coordinator.start()
            runCurrent()

            env.coordinator.stop()

            assertEquals(listOf("final_telemetry", "runtime_stop"), env.coordinator.stopLifecycleEvents)
        }

    @Test
    fun `production stop retries final telemetry before runtime teardown`() =
        runTest {
            val env = newEnv().also { it.coordinator.finalTelemetryFailuresRemaining = 1 }
            env.coordinator.start()
            runCurrent()

            backgroundScope.launch { env.coordinator.stop() }
            runCurrent()
            assertEquals(1, env.coordinator.finalTelemetryCalls)
            assertEquals(listOf("final_telemetry"), env.coordinator.stopLifecycleEvents)

            advanceTimeBy(TerminalTelemetryRetryDelayMillis)
            runCurrent()

            assertEquals(2, env.coordinator.finalTelemetryCalls)
            assertEquals(
                listOf("final_telemetry", "final_telemetry", "runtime_stop"),
                env.coordinator.stopLifecycleEvents,
            )
        }

    @Test
    fun `production stop rethrows final telemetry cancellation after mandatory teardown`() =
        runTest {
            val env = newEnv().also { it.coordinator.cancelFinalTelemetry = true }
            env.coordinator.start()
            runCurrent()

            val failure = runCatching { env.coordinator.stop() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(1, env.coordinator.finalTelemetryCalls)
            assertEquals(1, env.coordinator.stopCalls)
            assertEquals(listOf("final_telemetry", "runtime_stop"), env.coordinator.stopLifecycleEvents)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(listOf(null), env.host.stopRequests)
        }

    @Test
    fun `failure callback exception still finalizes guarded stop before rethrow`() =
        runTest {
            val env = newEnv()
            env.coordinator.start()
            runCurrent()

            val failure =
                runCatching {
                    env.coordinator.failAndStopWithBeforeFinalizationFailure(
                        IllegalStateException("failure callback crashed"),
                    )
                }.exceptionOrNull()

            assertEquals("failure callback crashed", failure?.message)
            assertEquals(1, env.coordinator.stopCalls)
            assertEquals(listOf("final_telemetry", "runtime_stop"), env.coordinator.stopLifecycleEvents)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(ServiceStatus.Failed, env.coordinator.statusTransitions.last())
            assertEquals(listOf(null), env.host.stopRequests)
        }

    @Test
    fun `failure callback cancellation still finalizes guarded stop before rethrow`() =
        runTest {
            val env = newEnv()
            val cancellation = CancellationException("failure callback cancelled")
            env.coordinator.start()
            runCurrent()

            val failure =
                runCatching {
                    env.coordinator.failAndStopWithBeforeFinalizationFailure(cancellation)
                }.exceptionOrNull()

            assertSame(cancellation, failure)
            assertEquals(1, env.coordinator.stopCalls)
            assertEquals(listOf("final_telemetry", "runtime_stop"), env.coordinator.stopLifecycleEvents)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(ServiceStatus.Failed, env.coordinator.statusTransitions.last())
            assertEquals(listOf(null), env.host.stopRequests)
        }

    @Test
    fun `cleanup pending during failure callback exception keeps retained runtime`() =
        runTest {
            val env = newEnv()
            env.coordinator.start()
            runCurrent()
            val retained = env.runtimeRegistry.current(Mode.Proxy)
            env.coordinator.cleanupPending = true

            val accepted =
                env.coordinator.failAndStopWithBeforeFinalizationFailure(
                    IllegalStateException("failure callback crashed"),
                )

            assertTrue(accepted)
            assertEquals(1, env.coordinator.stopCalls)
            assertEquals(listOf("final_telemetry", "runtime_stop"), env.coordinator.stopLifecycleEvents)
            assertSame(retained, env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(ServiceStatus.Failed, env.coordinator.statusTransitions.last())
            assertTrue(env.host.stopRequests.isEmpty())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRuntimeHandoverTest {
    @Test
    fun nonActionableAndCooldownHandoverEventsAreIgnored() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint =
                sampleFingerprint(dnsServers = listOf("8.8.8.8")).copy(
                    networkValidated = false,
                    captivePortalDetected = false,
                )
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
            assertEquals(
                false,
                env.handoverEvents.published
                    .single()
                    .currentNetworkValidated,
            )
            assertEquals(
                false,
                env.handoverEvents.published
                    .single()
                    .currentCaptivePortalDetected,
            )

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

    @Test
    fun captivePortalHandoverIsDeferredWithoutRestart() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val captivePortalFingerprint =
                sampleFingerprint(dnsServers = listOf("8.8.8.8")).copy(captivePortalDetected = true)
            val env = newEnv(fingerprint = initialFingerprint)

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = captivePortalFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            assertEquals(0, env.coordinator.restartCalls)
            assertTrue(env.handoverEvents.published.isEmpty())
        }

    @Test
    fun `connectivity restore rebinds runtime and replays policy`() =
        runTest {
            val restoredFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env = newEnv(fingerprint = null)

            env.coordinator.start()
            runCurrent()

            (env.runtimeRegistry.current(Mode.Proxy) as ProxyRuntimeSession).apply {
                lastSuccessfulHandoverFingerprintHash = restoredFingerprint.scopeKey()
                lastSuccessfulHandoverAt = env.clock.nowMillis()
            }

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = null,
                    currentFingerprint = restoredFingerprint,
                    classification = "connectivity_restore",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            assertEquals(1, env.coordinator.restartCalls)
            assertEquals(
                "handover",
                env.runtimeRegistry
                    .current(Mode.Proxy)
                    ?.activeConnectionPolicy
                    ?.value
                    ?.restartReason,
            )
            assertEquals(
                "connectivity_restore",
                env.handoverEvents.published
                    .single()
                    .classification,
            )
            assertEquals(
                restoredFingerprint.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )
        }

    @Test
    fun handoverRetryExhaustionTransitionsToFailedAndStops() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 5
                }

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            advanceTimeBy(31_000L)
            repeat(6) { runCurrent() }

            assertEquals(5, env.coordinator.restartCalls)
            assertEquals(1, env.coordinator.stopCalls)
            assertEquals(
                listOf(
                    ServiceStatus.Connected,
                    ServiceStatus.Failed,
                    ServiceStatus.Disconnected,
                ),
                env.coordinator.statusTransitions,
            )
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun `internal handover timeout is retried instead of treated as external cancellation`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverTimeoutsRemaining = 1
                }

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            advanceTimeBy(2_001L)
            runCurrent()

            assertEquals(2, env.coordinator.restartCalls)
            assertEquals(
                newFingerprint.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )
        }

    @Test
    fun stopCancelsScheduledHandoverRetry() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 1
                }

            env.coordinator.start()
            runCurrent()

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

            env.coordinator.stop()
            runCurrent()
            env.clock.advanceBy(31_000L)
            advanceTimeBy(31_000L)
            repeat(4) { runCurrent() }

            assertEquals(1, env.coordinator.restartCalls)
            assertEquals(1, env.coordinator.stopCalls)
            assertTrue(env.handoverEvents.published.isEmpty())
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun `stop invalidates active retry and completes suspending runtime shutdown`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val retryGate = CompletableDeferred<Unit>()
            val runtimeStopGate = CompletableDeferred<Unit>()
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 1
                    it.coordinator.handoverRestartGate = retryGate
                    it.coordinator.stopGate = runtimeStopGate
                }

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(2, env.coordinator.restartCalls)

            backgroundScope.launch { env.coordinator.stop() }
            runCurrent()
            retryGate.complete(Unit)
            runCurrent()

            assertEquals(1, env.coordinator.stopCalls)
            assertTrue(env.handoverEvents.published.isEmpty())
            runtimeStopGate.complete(Unit)
            runCurrent()

            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(
                listOf(ServiceStatus.Connected, ServiceStatus.Disconnected),
                env.coordinator.statusTransitions,
            )
        }

    @Test
    fun `stop cancels handover while policy resolution is pending`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val resolutionGate = CompletableDeferred<Unit>()
            val env = newEnv(fingerprint = initialFingerprint)
            env.coordinator.handoverResolutionGates[newFingerprint.scopeKey()] = resolutionGate

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            backgroundScope.launch { env.coordinator.stop() }
            runCurrent()

            assertTrue(!resolutionGate.isCompleted)
            assertEquals(0, env.coordinator.restartCalls)
            assertEquals(1, env.coordinator.stopCalls)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun `new handover invalidates paused exhausted failure from older event`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val fingerprintA = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val fingerprintB = sampleFingerprint(dnsServers = listOf("9.9.9.9"))
            val retainGate = CompletableDeferred<Unit>()
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 5
                    it.coordinator.handoverRetainGate = retainGate
                }

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = fingerprintA,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            advanceTimeBy(31_000L)
            repeat(6) { runCurrent() }
            assertEquals(1, env.coordinator.handoverRetainCalls)
            assertEquals(ServiceStatus.Connected, env.coordinator.statusTransitions.last())

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = fingerprintA,
                    currentFingerprint = fingerprintB,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            retainGate.complete(Unit)
            repeat(8) { runCurrent() }

            assertTrue(env.coordinator.statusTransitions.none { it == ServiceStatus.Failed })
            assertEquals(ServiceStatus.Connected, env.coordinator.statusTransitions.last())
            assertEquals(
                fingerprintB.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )
        }

    @Test
    fun `stop invalidates paused exhausted failure from older handover`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val fingerprintA = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val retainGate = CompletableDeferred<Unit>()
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 5
                    it.coordinator.handoverRetainGate = retainGate
                }

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = fingerprintA,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            advanceTimeBy(31_000L)
            repeat(6) { runCurrent() }
            assertEquals(1, env.coordinator.handoverRetainCalls)
            assertEquals(ServiceStatus.Connected, env.coordinator.statusTransitions.last())

            val stopJob = launch { env.coordinator.stop() }
            runCurrent()
            assertFalse(stopJob.isCompleted)

            retainGate.complete(Unit)
            repeat(8) { runCurrent() }
            stopJob.join()

            assertTrue(env.coordinator.statusTransitions.none { it == ServiceStatus.Failed })
            assertEquals(ServiceStatus.Disconnected, env.coordinator.statusTransitions.last())
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun `new handover suppresses stale retry from older event`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val fingerprintA = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val fingerprintB = sampleFingerprint(dnsServers = listOf("9.9.9.9"))
            val env =
                newEnv(fingerprint = initialFingerprint).also {
                    it.coordinator.handoverFailuresRemaining = 1
                }

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = fingerprintA,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            assertEquals(1, env.coordinator.restartCalls)

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = fingerprintA,
                    currentFingerprint = fingerprintB,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            assertEquals(2, env.coordinator.restartCalls)
            assertEquals(
                fingerprintB.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )

            advanceTimeBy(31_000L)
            repeat(4) { runCurrent() }

            assertEquals(2, env.coordinator.restartCalls)
            assertEquals(
                fingerprintB.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )
        }

    @Test
    fun `new handover invalidates older event while policy resolution is pending`() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val fingerprintA = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val fingerprintB = sampleFingerprint(dnsServers = listOf("9.9.9.9"))
            val resolutionGate = CompletableDeferred<Unit>()
            val env = newEnv(fingerprint = initialFingerprint)
            env.coordinator.handoverResolutionGates[fingerprintA.scopeKey()] = resolutionGate

            env.coordinator.start()
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = fingerprintA,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()
            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = fingerprintA,
                    currentFingerprint = fingerprintB,
                    classification = "link_refresh",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            assertTrue(!resolutionGate.isCompleted)
            assertEquals(1, env.coordinator.restartCalls)
            assertEquals(
                fingerprintB.scopeKey(),
                env.handoverEvents.published
                    .single()
                    .currentFingerprintHash,
            )
        }

    @Test
    fun lateHandoverEventsAreIgnoredAfterCoordinatorStops() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env = newEnv(fingerprint = initialFingerprint)

            env.coordinator.start()
            runCurrent()
            env.coordinator.stop()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = env.clock.nowMillis(),
                ),
            )
            runCurrent()

            assertEquals(0, env.coordinator.restartCalls)
            assertTrue(env.handoverEvents.published.isEmpty())
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedRuntimeCleanupTest {
    @Test
    fun `pending native cleanup retains service registration and blocks replacement until retry`() =
        runTest {
            val env = newEnv()
            env.coordinator.cleanupPending = true
            env.coordinator.start()
            runCurrent()
            val original = env.runtimeRegistry.current(Mode.Proxy)
            env.coordinator.stop(stopSelfStartId = 7)
            assertSame(original, env.runtimeRegistry.current(Mode.Proxy))
            assertTrue(env.host.stopRequests.isEmpty())
            assertEquals(ServiceStatus.Failed, env.coordinator.statusTransitions.last())
            env.coordinator.start()
            assertEquals(1, env.coordinator.startCalls)
            env.coordinator.cleanupPending = false
            env.coordinator.stop(stopSelfStartId = 8)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(listOf(8), env.host.stopRequests)
        }

    @Test
    fun `stale native exit cannot stop a replacement session`() =
        runTest {
            val env = newEnv()
            env.coordinator.start()
            runCurrent()
            val first = env.runtimeRegistry.current(Mode.Proxy)
            val guard =
                RuntimeStopGuard(
                    isCurrent = { env.runtimeRegistry.current(Mode.Proxy) === first },
                    failureReason = FailureReason.NativeError("Xray exited"),
                )
            env.coordinator.stop()
            env.coordinator.start()
            runCurrent()
            val replacement = env.runtimeRegistry.current(Mode.Proxy)
            env.coordinator.stop(guard = guard)
            assertSame(replacement, env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(1, env.coordinator.stopCalls)
            env.coordinator.stop(guard = RuntimeStopGuard({ true }, FailureReason.NativeError("Xray exited")))
            assertEquals(ServiceStatus.Failed, env.coordinator.statusTransitions.last())
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun `start retries retained cleanup before creating replacement`() =
        runTest {
            val env = newEnv()
            env.coordinator.start()
            runCurrent()
            env.coordinator.cleanupPending = true
            env.coordinator.stop()
            assertEquals(1, env.coordinator.startCalls)
            env.coordinator.cleanupPending = false
            env.coordinator.start()
            runCurrent()
            assertEquals(2, env.coordinator.startCalls)
            assertEquals(ServiceStatus.Connected, env.coordinator.statusTransitions.last())
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
            assertTrue(env.host.stopRequests.isEmpty())
        }
}

@Suppress("UnusedParameter")
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
            permissionWatchdog = TestPermissionWatchdog(),
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

@OptIn(ExperimentalCoroutinesApi::class)
private class TestCoordinator(
    host: ServiceCoordinatorHost,
    resolver: ConnectionPolicyResolver,
    runtimeRegistry: ServiceRuntimeRegistry,
    rememberedStore: TestRememberedNetworkPolicyStore,
    handoverMonitor: TestNetworkHandoverMonitor,
    handoverEvents: TestPolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
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
        permissionWatchdog = permissionWatchdog,
        ioDispatcher = dispatcher,
        clock = clock,
    ) {
    var failOnStart: Boolean = false
    var failOnStop: Boolean = false
    var cleanupPending: Boolean = false
    var stopGate: CompletableDeferred<Unit>? = null
    var startCalls: Int = 0
    var stopCalls: Int = 0
    var restartCalls: Int = 0
    var handoverFailuresRemaining: Int = 0
    var handoverTimeoutsRemaining: Int = 0
    var handoverRestartGate: CompletableDeferred<Unit>? = null
    var handoverRetainGate: CompletableDeferred<Unit>? = null
    var handoverRetainCalls: Int = 0
    var handoverRetainResult: Boolean = false
    var finalTelemetryFailuresRemaining: Int = 0
    var finalTelemetryCalls: Int = 0
    var cancelFinalTelemetry: Boolean = false
    var readySnapshot: NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = "proxy")
    var publishedEvidence: RuntimeStartEvidence? = null
    var publishedRuntimeId: String? = null
    val handoverResolutionGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val statusTransitions = mutableListOf<ServiceStatus>()
    val startLifecycleEvents = mutableListOf<String>()
    val stopLifecycleEvents = mutableListOf<String>()

    override val runtimeHooks: ServiceRuntimeModeHooks<ProxyRuntimeSession> =
        ServiceRuntimeModeHooks(
            serviceLabel = "test",
            startHooks =
                ServiceRuntimeStartHooks(
                    createRuntimeSession = ::createRuntimeSession,
                    resolveInitialConnectionPolicy = ::resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
                    startResolvedRuntime = ::startResolvedRuntime,
                    publishRuntimeStartEvidence = ::publishRuntimeStartEvidence,
                    startModeTelemetryUpdates = ::startModeTelemetryUpdates,
                ),
            stopHooks =
                ServiceRuntimeStopHooks(
                    captureFinalTelemetry = ::captureFinalTelemetry,
                    stopModeRuntime = ::stopModeRuntime,
                ),
            handoverHooks =
                ServiceRuntimeHandoverHooks(
                    resolveConnectionPolicy = ::resolveHandoverConnectionPolicy,
                    restartAfterHandover = ::restartAfterHandover,
                    classifyFailure = ::classifyHandoverFailure,
                    retainFailClosedAfterExhaustion = ::retainFailClosedAfterExhaustion,
                ),
            statusHooks =
                ServiceRuntimeStatusHooks(
                    updateStatus = ::updateStatus,
                    classifyStartupFailure = ::classifyStartupFailure,
                ),
        )

    private fun createRuntimeSession(): ProxyRuntimeSession = ProxyRuntimeSession()

    private suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        sampleResolution(mode = Mode.Proxy)

    @Suppress("UnusedParameter")
    private suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution {
        handoverResolutionGates[fingerprint.scopeKey()]?.await()
        return sampleResolution(mode = Mode.Proxy, policySignature = "handover")
    }

    private fun applyActiveConnectionPolicy(
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

    @Suppress("UnusedParameter")
    private suspend fun startResolvedRuntime(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ): RuntimeStartEvidence {
        startLifecycleEvents += "runtime_start"
        startCalls += 1
        if (failOnStart) {
            error("boom")
        }
        return RuntimeStartEvidence.ProxySnapshot(readySnapshot)
    }

    @Suppress("UnusedParameter")
    private suspend fun publishRuntimeStartEvidence(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        evidence: RuntimeStartEvidence,
    ) {
        publishedRuntimeId = session.runtimeId
        publishedEvidence = evidence
        startLifecycleEvents += "publish_evidence:${session.runtimeId}"
    }

    @Suppress("UnusedParameter")
    private suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        stopLifecycleEvents += "runtime_stop"
        stopCalls += 1
        if (cleanupPending) throw RuntimeCleanupPendingException()
        if (failOnStop) error("stop failed")
        stopGate?.await()
    }

    private suspend fun captureFinalTelemetry() {
        finalTelemetryCalls += 1
        stopLifecycleEvents += "final_telemetry"
        if (cancelFinalTelemetry) throw CancellationException("cancel final telemetry")
        if (finalTelemetryFailuresRemaining > 0) {
            finalTelemetryFailuresRemaining -= 1
            error("final telemetry failed")
        }
    }

    @Suppress("UnusedParameter")
    private fun startModeTelemetryUpdates(replaceTelemetryJob: TelemetryJobReplacer) = Unit

    private suspend fun restartAfterHandover(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        restartCalls += 1
        if (handoverFailuresRemaining > 0) {
            handoverFailuresRemaining -= 1
            error("handover boom")
        }
        if (handoverTimeoutsRemaining > 0) {
            handoverTimeoutsRemaining -= 1
            withTimeout(1L) { awaitCancellation() }
        }
        handoverRestartGate?.await()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "handover",
            appliedAt = appliedAt,
        )
    }

    private suspend fun retainFailClosedAfterExhaustion(): Boolean {
        handoverRetainCalls += 1
        handoverRetainGate?.await()
        return handoverRetainResult
    }

    suspend fun failAndStopWithBeforeFinalizationFailure(failure: Throwable): Boolean =
        failAndStopRuntime(
            failureReason = FailureReason.NativeError("terminal failure"),
            beforeStopFinalization = { throw failure },
        )

    @Suppress("UnusedParameter")
    private fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        status = newStatus
        statusTransitions += newStatus
        startLifecycleEvents += "status:$newStatus"
    }

    private fun classifyStartupFailure(error: Exception): FailureReason = FailureReason.Unexpected(error)

    private fun classifyHandoverFailure(error: Exception): FailureReason = FailureReason.Unexpected(error)
}

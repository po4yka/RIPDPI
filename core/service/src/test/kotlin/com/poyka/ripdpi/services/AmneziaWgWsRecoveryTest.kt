package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AmneziaWgWsRecoveryTest {
    @Test
    fun establishedWsExitStartsFreshAuthenticatedRuntimeAfterBackoff() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val exits = mutableListOf<SupervisorExitCause>()
            supervisor.start(request()) { exits += it }
            val first = factory.lastRuntime
            first.complete(2)
            runCurrent()
            assertEquals(1, factory.runtimes.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, factory.runtimes.size)
            assertNotSame(first, supervisor.runtime)
            assertTrue(exits.isEmpty())
            supervisor.stop()
        }

    @Test
    fun stopDuringBackoffDoesNotCreateReplacement() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            supervisor.start(request()) {}
            factory.lastRuntime.complete(2)
            runCurrent()
            supervisor.stop()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, factory.runtimes.size)
            assertEquals(null, supervisor.runtime)
        }

    @Test
    fun exhaustedRecoveryReportsOneFailureAndDoesNotRetryAgain() =
        runTest {
            var count = 0
            val factory =
                TestRipDpiAmneziaWgFactory {
                    TestAmneziaWgRuntime().apply { if (++count > 1) startFailure = IOException("unavailable") }
                }
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val exits = mutableListOf<SupervisorExitCause>()
            supervisor.start(request()) { exits += it }
            factory.lastRuntime.complete(2)
            runCurrent()
            repeat(3) {
                advanceTimeBy(10_000)
                runCurrent()
            }
            assertEquals(4, factory.runtimes.size)
            assertEquals(1, exits.size)
            supervisor.stop()
        }

    @Test
    fun stopDuringRecoveryReadinessStopsTheOwnedWorker() =
        runTest {
            var created = 0
            val factory =
                TestRipDpiAmneziaWgFactory {
                    TestAmneziaWgRuntime().apply { if (++created > 1) awaitReadyGate = CompletableDeferred() }
                }
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val exits = mutableListOf<SupervisorExitCause>()
            supervisor.start(request()) { exits += it }
            factory.lastRuntime.complete(2)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, factory.runtimes.size)
            supervisor.stop()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, factory.lastRuntime.stopCount)
            assertEquals(2, factory.runtimes.size)
            assertEquals(null, supervisor.runtime)
            assertTrue(exits.isEmpty())
        }

    @Test
    fun eachRecoveryResolvesTheCurrentUnderlayAgain() =
        runTest {
            var lookups = 0
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = { listOf("192.0.2.${++lookups}") },
                )
            supervisor.start(request()) {}
            factory.lastRuntime.complete(2)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf("192.0.2.2"), factory.lastRuntime.lastConfig?.carrierWsAddresses)
            supervisor.stop()
        }

    @Test
    fun queuedOldExitCannotRecoverOrStopReplacementSession() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val exits = mutableListOf<SupervisorExitCause>()
            supervisor.start(request()) { exits += it }
            factory.lastRuntime.complete(2)
            supervisor.stop()
            supervisor.start(request().copy(profileId = "replacement")) { exits += it }
            val replacement = factory.lastRuntime
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(supervisor.runtime === replacement)
            assertEquals(0, replacement.stopCount)
            assertEquals(2, factory.runtimes.size)
            assertTrue(exits.none { it !is SupervisorExitCause.ExpectedStop })
            supervisor.stop()
        }

    @Test
    fun stopBetweenResolutionAndAdoptionDoesNotLaunchNativeRuntime() =
        runTest {
            lateinit var supervisor: AmneziaWgRuntimeSupervisor
            val factory =
                TestRipDpiAmneziaWgFactory {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { supervisor.stop() }
                    TestAmneziaWgRuntime()
                }
            supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val error = runCatching { supervisor.start(request()) {} }.exceptionOrNull()
            runCurrent()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals(null, factory.lastRuntime.lastConfig)
            assertEquals(null, supervisor.runtime)
        }

    @Test
    fun vpnBootstrapSuppliesNumericAddressesWithoutChangingIdentity() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = { host ->
                        assertEquals("carrier.example.test", host)
                        listOf("192.0.2.7", "2001:db8::7")
                    },
                )
            supervisor.start(request()) {}
            assertEquals(listOf("192.0.2.7", "2001:db8::7"), factory.lastRuntime.lastConfig?.carrierWsAddresses)
            assertEquals("wss://carrier.example.test/wg", factory.lastRuntime.lastConfig?.carrierWsUrl)
            supervisor.stop()
        }

    @Test
    fun bootstrapCanonicalizesIdnButPreservesUrlIdentity() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = { host ->
                        assertEquals("xn--e1afmkfd.xn--p1ai", host)
                        listOf("192.0.2.7")
                    },
                )
            val url = "wss://пример.рф/wg"
            supervisor.start(request().copy(carrierWsUrl = url)) {}
            assertEquals(url, factory.lastRuntime.lastConfig?.carrierWsUrl)
            supervisor.stop()
        }

    @Test
    fun ipv6LiteralPinsAddressWithoutLookup() =
        runTest {
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = { error("numeric literal must not resolve") },
                )
            supervisor.start(request().copy(carrierWsUrl = "wss://[2001:db8::7]:8443/wg")) {}
            assertEquals(listOf("2001:db8::7"), factory.lastRuntime.lastConfig?.carrierWsAddresses)
            supervisor.stop()
        }

    @Test
    fun stopDuringBootstrapDiscardsLateLookupAndCreatesNoRuntime() =
        runTest {
            val lookup = CompletableDeferred<List<String>>()
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = { lookup.await() },
                )
            val starting = launch { runCatching { supervisor.start(request()) {} } }
            runCurrent()
            supervisor.stop()
            lookup.complete(listOf("192.0.2.7"))
            starting.join()
            assertTrue(factory.runtimes.isEmpty())
        }

    @Test
    fun cancellingStartCancelsOwnedBootstrapBeforeClearingOwnership() =
        runTest {
            val cancelled = CompletableDeferred<Unit>()
            val factory = TestRipDpiAmneziaWgFactory()
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    factory,
                    RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                    resolveWsEndpoint = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                )
            val starting = launch { supervisor.start(request()) {} }
            runCurrent()
            starting.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
            supervisor.stop()
            assertTrue(factory.runtimes.isEmpty())
        }

    private fun request() =
        AwgActivationRequest(
            profileId = "ws-test",
            privateKey = "private",
            peerPublicKey = "peer",
            endpointHost = "unused.example.test",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            carrier = AwgActivationRequest.CARRIER_WS,
            carrierWsUrl = "wss://carrier.example.test/wg",
        )
}

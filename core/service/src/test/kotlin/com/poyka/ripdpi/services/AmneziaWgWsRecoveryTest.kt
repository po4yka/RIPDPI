package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmneziaWgWsRecoveryTest {
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

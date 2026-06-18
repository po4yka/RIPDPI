package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [DefaultStandaloneAmneziaWgActivator]: the `:app`-callable hop that maps an
 * [AwgActivationRequest] into the native config and drives the supervisor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneAmneziaWgActivatorTest {
    @Test
    fun `activate resolves the request and starts the native runtime with it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val resolver = RecordingAmneziaWgRuntimeConfigResolver()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    applicationScope = backgroundScope,
                    dispatchers = dispatchers(dispatcher),
                    supervisorFactory = AmneziaWgRuntimeSupervisorFactory(factory, resolver),
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            advanceUntilIdle()

            assertEquals("awg-uuid-A", resolver.lastRequest?.profileId)
            assertEquals(1, factory.runtimes.size)
            assertEquals("awg-uuid-A", factory.lastRuntime.lastConfig?.profileId)
            assertEquals(51820, factory.lastRuntime.lastConfig?.endpointPort)
            assertEquals(
                7,
                factory.lastRuntime.lastConfig
                    ?.amnezia
                    ?.s3,
            )

            activator.deactivate()
            advanceUntilIdle()
            assertEquals(1, factory.lastRuntime.stopCount)
        }

    @Test
    fun `re-activating stops the previous tunnel before starting a new one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    applicationScope = backgroundScope,
                    dispatchers = dispatchers(dispatcher),
                    supervisorFactory =
                        AmneziaWgRuntimeSupervisorFactory(factory, RecordingAmneziaWgRuntimeConfigResolver()),
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            advanceUntilIdle()
            activator.activate(sampleRequest("awg-uuid-B"))
            advanceUntilIdle()

            assertEquals(2, factory.runtimes.size)
            assertEquals(1, factory.runtimes[0].stopCount)
            assertEquals("awg-uuid-B", factory.runtimes[1].lastConfig?.profileId)
        }

    @Test
    fun `deactivate without an active tunnel is a no-op`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiAmneziaWgFactory()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    applicationScope = backgroundScope,
                    dispatchers = dispatchers(dispatcher),
                    supervisorFactory =
                        AmneziaWgRuntimeSupervisorFactory(factory, RecordingAmneziaWgRuntimeConfigResolver()),
                )

            activator.deactivate()
            advanceUntilIdle()

            assertEquals(0, factory.runtimes.size)
        }

    private fun dispatchers(dispatcher: CoroutineDispatcher): AppCoroutineDispatchers =
        AppCoroutineDispatchers(default = dispatcher, io = dispatcher, main = dispatcher)

    private fun sampleRequest(profileId: String): AwgActivationRequest =
        AwgActivationRequest(
            profileId = profileId,
            privateKey = "privkey==",
            peerPublicKey = "peerpub==",
            endpointHost = "vpn.example.org",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            obfuscation = AwgActivationObfuscation(jc = 4, s3 = 7, s4 = 9),
        )
}

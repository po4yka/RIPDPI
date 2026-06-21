package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [DefaultStandaloneAmneziaWgActivator]: the `:app`-callable hop that
 * selects an [AwgActivationRequest] and starts the owned VPN/protect lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneAmneziaWgActivatorTest {
    @Test
    fun `activate selects request and starts vpn service`() =
        runTest {
            val serviceController = RecordingServiceController()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    serviceController = serviceController,
                )
            val request = sampleRequest("awg-uuid-A")

            activator.activate(request)

            assertEquals(listOf(Mode.VPN), serviceController.startCalls)
            assertEquals(request, activator.selectedAwgEgress())
        }

    @Test
    fun `re-activating replaces selected request and restarts vpn`() =
        runTest {
            val serviceController = RecordingServiceController()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    serviceController = serviceController,
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            val second = sampleRequest("awg-uuid-B")
            activator.activate(second)

            assertEquals(listOf(Mode.VPN, Mode.VPN), serviceController.startCalls)
            assertEquals(second, activator.selectedAwgEgress())
        }

    @Test
    fun `deactivate clears selection and stops owned vpn session`() =
        runTest {
            val serviceController = RecordingServiceController()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    serviceController = serviceController,
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            activator.deactivate()

            assertNull(activator.selectedAwgEgress())
            assertEquals(1, serviceController.stopCalls)
        }

    @Test
    fun `deactivate without selected request does not stop vpn service`() =
        runTest {
            val serviceController = RecordingServiceController()
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    serviceController = serviceController,
                )

            activator.deactivate()

            assertNull(activator.selectedAwgEgress())
            assertEquals(0, serviceController.stopCalls)
        }

    @Test
    fun `rejected vpn start clears selection and fails activation`() =
        runTest {
            val serviceController =
                RecordingServiceController(
                    startResult = ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing),
                )
            val activator =
                DefaultStandaloneAmneziaWgActivator(
                    serviceController = serviceController,
                )

            try {
                activator.activate(sampleRequest("awg-uuid-A"))
                fail("Expected rejected VPN start to fail activation")
            } catch (_: IllegalStateException) {
            }

            assertNull(activator.selectedAwgEgress())
            assertEquals(listOf(Mode.VPN), serviceController.startCalls)
        }

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

    private class RecordingServiceController(
        private val startResult: ServiceStartResult = ServiceStartResult.Accepted(Mode.VPN),
    ) : ServiceController {
        val startCalls = mutableListOf<Mode>()
        var stopCalls = 0

        override fun start(mode: Mode): ServiceStartResult {
            startCalls += mode
            return startResult
        }

        override fun stop() {
            stopCalls++
        }
    }
}

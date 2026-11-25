package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.core.WarpProbeSocketController
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AwgEndpointCheckTest {
    private class RecordingBindings(
        private val answer: String?,
    ) : RipDpiWarpEndpointProbeBindings {
        val requests = mutableListOf<String>()

        override fun probeEndpoint(
            requestJson: String,
            controller: WarpProbeSocketController,
        ): String? {
            requests += requestJson
            return answer
        }
    }

    private fun check(bindings: RecordingBindings) = NativeAwgEndpointCheck(bindings, ActiveProtectSocketPathProvider())

    @Test
    fun `an answered handshake reports its round trip`() =
        runTest {
            val bindings = RecordingBindings("""{"host":"vpn.example.org","port":51820,"rttMs":42}""")

            assertEquals(AwgEndpointCheckResult.Answered(42), check(bindings).check(request()))
        }

    @Test
    fun `no handshake answer is a failed check`() =
        runTest {
            assertEquals(AwgEndpointCheckResult.NoAnswer, check(RecordingBindings(null)).check(request()))
        }

    @Test
    fun `the probe carries the profile keys and obfuscation`() =
        runTest {
            val bindings = RecordingBindings(null)
            check(bindings).check(request())

            val sent = Json.parseToJsonElement(bindings.requests.single()).jsonObject
            val amnezia = sent.getValue("amnezia").jsonObject
            assertEquals(
                "vpn.example.org",
                sent
                    .getValue("endpoint")
                    .jsonObject
                    .getValue("host")
                    .jsonPrimitive.content,
            )
            assertEquals(PeerKey, sent.getValue("peerPublicKey").jsonPrimitive.content)
            assertEquals(PresharedKey, amnezia.getValue("presharedKey").jsonPrimitive.content)
            assertEquals(
                4,
                amnezia
                    .getValue("jc")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(true, amnezia.getValue("enabled").jsonPrimitive.boolean)
            assertFalse("v3" in amnezia)
        }

    @Test
    fun `plain WireGuard probes without the obfuscation codec`() {
        val probe = request().copy(obfuscation = AwgActivationObfuscation()).toProbeRequest()

        assertEquals(false, probe?.amnezia?.enabled)
    }

    @Test
    fun `a WebSocket carrier or an incomplete profile cannot be checked`() =
        runTest {
            val bindings = RecordingBindings(null)
            val ws = request().copy(carrier = AwgActivationRequest.CARRIER_WS, carrierWsUrl = "wss://edge.example/ws")

            assertEquals(AwgEndpointCheckResult.Unsupported, check(bindings).check(ws))
            assertEquals(AwgEndpointCheckResult.Unsupported, check(bindings).check(request().copy(privateKey = "")))
            assertEquals(emptyList<String>(), bindings.requests)
            assertNull(ws.toProbeRequest())
        }

    private fun request() =
        AwgActivationRequest(
            profileId = "awg-1",
            privateKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            peerPublicKey = PeerKey,
            presharedKey = PresharedKey,
            endpointHost = "vpn.example.org",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            obfuscation = AwgActivationObfuscation(jc = 4, jmin = 40, jmax = 70),
        )

    private companion object {
        const val PeerKey = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA="
        const val PresharedKey = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDA="
    }
}

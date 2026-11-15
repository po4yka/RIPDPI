package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class NativeEchTlsHandshakeTest {
    @Test
    fun connectSendsOriginAndDialEndpointWithEchConfigBytesToNativeBridge() =
        runTest {
            val bindings =
                FakeNativeEchTlsHandshakeBindings(
                    responseJson = """{"negotiatedEch":true,"latencyMs":42}""",
                )

            val handshake = NativeEchTlsHandshake(bindings)
            val result =
                handshake.connect(
                    endpoint = EchTlsEndpoint("cloudflare.com", "edge.example", 8443),
                    echConfig = byteArrayOf(1, 2, 3),
                )

            assertEquals(true, result.negotiatedEch)
            assertEquals(42L, result.latencyMs)
            assertTrue(bindings.lastRequestJson.contains(""""origin":"cloudflare.com""""))
            assertTrue(bindings.lastRequestJson.contains(""""connectHost":"edge.example""""))
            assertTrue(bindings.lastRequestJson.contains(""""connectPort":8443"""))
            assertTrue(
                bindings.lastRequestJson.contains(
                    """"echConfigBytesB64":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"""",
                ),
            )
            assertTrue(bindings.lastRequestJson.contains(""""connectTimeoutMs":10000"""))
        }

    @Test
    fun connectThrowsIoExceptionForNativeErrorPayload() =
        runTest {
            val bindings =
                FakeNativeEchTlsHandshakeBindings(
                    responseJson = """{"error":"ech_required"}""",
                )

            val error =
                runCatching {
                    NativeEchTlsHandshake(bindings).connect(
                        endpoint = EchTlsEndpoint("cloudflare.com", "edge.example", 8443),
                        echConfig = byteArrayOf(1, 2, 3),
                    )
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals("ech_required", error?.message)
        }

    @Test
    fun unacceptedConfigIsASetupError() =
        runTest {
            val native =
                NativeEchTlsHandshake(FakeNativeEchTlsHandshakeBindings("""{"transportSetup":"unusableConfig"}"""))
            val error =
                runCatching {
                    native.connect(EchTlsEndpoint("origin.example", "edge.example", 8443), TestEchConfigList)
                }.exceptionOrNull()
            assertTrue(error is EchTlsSetupException)
            assertEquals(EchTlsSetupFailure.UNUSABLE_CONFIG, (error as EchTlsSetupException).failure)
        }

    @Test
    fun incompleteResultIsABridgeFailure() =
        runTest {
            val invalidPayloads =
                listOf(
                    "{}",
                    """{"negotiatedEch":null}""",
                    """{"negotiatedEch":false,"latencyMs":4}""",
                    """{"negotiatedEch":true}""",
                    """{"negotiatedEch":true,"latencyMs":-1}""",
                )
            for (payload in invalidPayloads) {
                val handshake = NativeEchTlsHandshake(FakeNativeEchTlsHandshakeBindings(payload))
                val error =
                    runCatching {
                        handshake.connect(EchTlsEndpoint("origin.example", "edge.example", 8443), TestEchConfigList)
                    }.exceptionOrNull()

                assertTrue(error is EchTlsSetupException)
                assertEquals(EchTlsSetupFailure.UNAVAILABLE, (error as EchTlsSetupException).failure)
            }
        }

    private class FakeNativeEchTlsHandshakeBindings(
        private val responseJson: String?,
    ) : NativeEchTlsHandshakeBindings {
        lateinit var lastRequestJson: String

        override fun createRequest(): Long = 1L

        override fun cancelRequest(requestHandle: Long) = Unit

        override fun releaseRequest(requestHandle: Long) = Unit

        override fun connect(
            requestHandle: Long,
            requestJson: String,
        ): String? {
            lastRequestJson = requestJson
            return responseJson
        }
    }
}

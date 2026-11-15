package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NativeEchTlsHandshakeTest {
    @Test
    fun connectSendsOriginAndDialEndpointWithEchConfigBytesToNativeBridge() =
        runTest {
            val bindings =
                FakeNativeEchTlsHandshakeBindings(
                    responseJson =
                        """{"outcome":"accepted","stage":"tls","peerAddress":"203.0.113.8:8443","latencyMs":42,"retryAttempted":true}""",
                )

            val handshake = NativeEchTlsHandshake(bindings)
            val result =
                handshake.connect(
                    endpoint = EchTlsEndpoint("cloudflare.com", "edge.example", 8443),
                    echConfig = byteArrayOf(1, 2, 3),
                )

            assertEquals(true, result.negotiatedEch)
            assertEquals(42L, result.latencyMs)
            assertEquals("203.0.113.8:8443", result.peerAddress)
            assertEquals(true, result.retryAttempted)
            assertTrue(bindings.lastRequestJson.contains(""""origin":"cloudflare.com""""))
            assertTrue(bindings.lastRequestJson.contains(""""connectHost":"edge.example""""))
            assertTrue(bindings.lastRequestJson.contains(""""connectPort":8443"""))
            assertTrue(
                bindings.lastRequestJson.contains(
                    """"echConfigBytesB64":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"""",
                ),
            )
            assertTrue(bindings.lastRequestJson.contains(""""connectTimeoutMs":5000"""))
        }

    @Test
    fun certificateFailurePreservesNativePeerAndStage() =
        runTest {
            val bindings =
                FakeNativeEchTlsHandshakeBindings(
                    responseJson =
                        """{"outcome":"certificateError","stage":"tls","peerAddress":"203.0.113.9:8443","latencyMs":31,"retryAttempted":false}""",
                )

            val error =
                runCatching {
                    NativeEchTlsHandshake(bindings).connect(
                        endpoint = EchTlsEndpoint("cloudflare.com", "edge.example", 8443),
                        echConfig = byteArrayOf(1, 2, 3),
                    )
                }.exceptionOrNull()

            assertTrue(error is EchTlsException)
            val failure = error as EchTlsException
            assertEquals(EchTlsOutcome.CERTIFICATE_ERROR, failure.outcome)
            assertEquals(EchTlsStage.TLS, failure.stage)
            assertEquals("203.0.113.9:8443", failure.peerAddress)
            assertEquals(31L, failure.latencyMs)
            assertEquals(false, failure.retryAttempted)
        }

    @Test
    fun unacceptedConfigIsASetupError() =
        runTest {
            val native =
                NativeEchTlsHandshake(
                    FakeNativeEchTlsHandshakeBindings(
                        """{"outcome":"unusableConfig","stage":"setup","peerAddress":null,"latencyMs":null,"retryAttempted":false}""",
                    ),
                )
            val error =
                runCatching {
                    native.connect(EchTlsEndpoint("origin.example", "edge.example", 8443), TestEchConfigList)
                }.exceptionOrNull()
            assertTrue(error is EchTlsException)
            assertEquals(EchTlsOutcome.UNUSABLE_CONFIG, (error as EchTlsException).outcome)
        }

    @Test
    fun incompleteResultIsABridgeFailure() =
        runTest {
            val invalidPayloads =
                listOf(
                    "{}",
                    """{"negotiatedEch":false,"latencyMs":4}""",
                    """{"outcome":"accepted","stage":"tls","latencyMs":4,"retryAttempted":false}""",
                    """{"outcome":"accepted","stage":"tls","peerAddress":null,"latencyMs":4,"retryAttempted":false}""",
                    """{"outcome":"accepted","stage":"setup","peerAddress":"203.0.113.9:8443","latencyMs":4,"retryAttempted":false}""",
                    """{"outcome":"accepted","stage":"tls","peerAddress":"203.0.113.9:8443","latencyMs":-1,"retryAttempted":false}""",
                )
            for (payload in invalidPayloads) {
                val handshake = NativeEchTlsHandshake(FakeNativeEchTlsHandshakeBindings(payload))
                val error =
                    runCatching {
                        handshake.connect(EchTlsEndpoint("origin.example", "edge.example", 8443), TestEchConfigList)
                    }.exceptionOrNull()

                assertTrue(error is EchTlsException)
                assertEquals(EchTlsOutcome.UNAVAILABLE, (error as EchTlsException).outcome)
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

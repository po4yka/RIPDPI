package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class NativeEchTlsHandshakeTest {
    @Test
    fun connectSendsTargetAndEchConfigBytesToNativeBridge() =
        runTest {
            val bindings =
                FakeNativeEchTlsHandshakeBindings(
                    responseJson = """{"negotiatedEch":true,"latencyMs":42}""",
                )

            val handshake = NativeEchTlsHandshake(bindings)
            val result =
                handshake.connect(
                    target = "cloudflare.com",
                    echConfig = byteArrayOf(1, 2, 3),
                )

            assertEquals(true, result.negotiatedEch)
            assertEquals(42L, result.latencyMs)
            assertTrue(bindings.lastRequestJson.contains(""""target":"cloudflare.com""""))
            assertTrue(
                bindings.lastRequestJson.contains(
                    """"echConfigBytesB64":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"""",
                ),
            )
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
                        target = "cloudflare.com",
                        echConfig = byteArrayOf(1, 2, 3),
                    )
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals("ech_required", error?.message)
        }

    private class FakeNativeEchTlsHandshakeBindings(
        private val responseJson: String?,
    ) : NativeEchTlsHandshakeBindings {
        lateinit var lastRequestJson: String

        override fun connect(requestJson: String): String? {
            lastRequestJson = requestJson
            return responseJson
        }
    }
}

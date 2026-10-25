package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiAmneziaWgLifecycleTest {
    @Test
    fun earlyExitClearsReadinessSignal() =
        runTest {
            val bindings =
                object : RipDpiAmneziaWgBindings {
                    override fun create(configJson: String): Long {
                        val payload = Json.parseToJsonElement(configJson).jsonObject
                        assertEquals(JsonPrimitive(1330), payload["mtu"])
                        val obfuscation = payload.getValue("amnezia").jsonObject
                        assertEquals(
                            setOf(
                                "wireProfile",
                                "jc",
                                "jmin",
                                "jmax",
                                "s1",
                                "s2",
                                "s3",
                                "s4",
                                "h1",
                                "h2",
                                "h3",
                                "h4",
                                "i1",
                                "i2",
                                "i3",
                                "i4",
                                "i5",
                            ),
                            obfuscation.keys,
                        )
                        assertEquals(JsonPrimitive("awg-3.1-ranges-v1"), obfuscation["wireProfile"])
                        return 1L
                    }

                    override fun start(handle: Long): Int = 0

                    override fun stop(handle: Long) = Unit

                    override fun pollTelemetry(handle: Long): String? = null

                    override fun destroy(handle: Long) = Unit
                }
            val awg = RipDpiAmneziaWg(bindings)
            val config =
                ResolvedRipDpiAmneziaWgConfig(
                    enabled = true,
                    profileId = "profile",
                    privateKey = "private-key",
                    peerPublicKey = "peer-public-key",
                    endpointHost = "example.test",
                    endpointPort = 443,
                    interfaceAddressV4 = "10.0.0.2/32",
                    localSocksHost = "127.0.0.1",
                    localSocksPort = 1080,
                )

            assertEquals(0, awg.start(config))
            assertTrue(runCatching { awg.awaitReady() }.exceptionOrNull() is NativeError.NotRunning)
        }
}

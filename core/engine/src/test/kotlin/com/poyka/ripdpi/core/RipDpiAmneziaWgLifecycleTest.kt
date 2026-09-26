package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiAmneziaWgLifecycleTest {
    @Test
    fun earlyExitClearsReadinessSignal() =
        runTest {
            val bindings =
                object : RipDpiAmneziaWgBindings {
                    override fun create(configJson: String): Long = 1L

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

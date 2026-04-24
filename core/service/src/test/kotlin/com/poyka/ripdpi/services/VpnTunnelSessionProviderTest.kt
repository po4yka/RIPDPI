package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelSessionProviderTest {
    @Test
    fun establishesSessionThroughHostBuilder() =
        runTest {
            val expected = TestVpnTunnelSession()
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                    ): VpnTunnelBuilder =
                        object : VpnTunnelBuilder {
                            override fun establish(): VpnTunnelSession = expected
                        }
                }

            val session = DefaultVpnTunnelSessionProvider().establish(host, dns = "1.1.1.1", ipv6 = true)

            assertSame(expected, session)
        }

    @Test
    fun throwsWhenBuilderDoesNotProduceSession() =
        runTest {
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                    ): VpnTunnelBuilder =
                        object : VpnTunnelBuilder {
                            override fun establish(): VpnTunnelSession? = null
                        }
                }

            val result =
                runCatching {
                    DefaultVpnTunnelSessionProvider().establish(host, dns = "1.1.1.1", ipv6 = false)
                }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
}

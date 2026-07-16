package com.poyka.ripdpi.services

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelSessionProviderTest {
    @Test
    fun establishesSessionThroughHostBuilder() =
        runTest {
            val expected = TestVpnTunnelSession()
            val expectedPlan = VpnAppRoutingPlan.AllowOnly(setOf("com.example.allowed"))
            var receivedPlan: VpnAppRoutingPlan? = null
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun resolveAppRoutingPlan(settings: AppSettings) = expectedPlan

                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                        appRoutingPlan: VpnAppRoutingPlan,
                        httpProxyPort: Int?,
                    ): VpnTunnelBuilder =
                        object : VpnTunnelBuilder {
                            override fun establish(): VpnTunnelSession = expected
                        }.also { receivedPlan = appRoutingPlan }
                }

            val session =
                DefaultVpnTunnelSessionProvider().establish(
                    host,
                    dns = "1.1.1.1",
                    ipv6 = true,
                    appRoutingPlan = expectedPlan,
                )

            assertSame(expected, session)
            assertSame(expectedPlan, receivedPlan)
        }

    @Test
    fun throwsWhenBuilderDoesNotProduceSession() =
        runTest {
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun resolveAppRoutingPlan(settings: AppSettings) =
                        VpnAppRoutingPlan.Disallow(emptySet())

                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                        appRoutingPlan: VpnAppRoutingPlan,
                        httpProxyPort: Int?,
                    ): VpnTunnelBuilder =
                        object : VpnTunnelBuilder {
                            override fun establish(): VpnTunnelSession? = null
                        }
                }

            val result =
                runCatching {
                    DefaultVpnTunnelSessionProvider().establish(
                        host,
                        dns = "1.1.1.1",
                        ipv6 = false,
                        appRoutingPlan = VpnAppRoutingPlan.Disallow(emptySet()),
                    )
                }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
}

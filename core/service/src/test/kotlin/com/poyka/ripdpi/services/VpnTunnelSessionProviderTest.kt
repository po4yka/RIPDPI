package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.routing.PackageRoutingRule
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
            val expectedSettings = AppSettings.getDefaultInstance()
            var receivedPlan: VpnAppRoutingPlan? = null
            var receivedSettings: AppSettings? = null
            val profile = VpnProfileInterface(listOf("10.0.0.1", "10.0.0.2"), listOf("10.0.0.0/8"), false, 1400)
            var receivedProfile: VpnProfileInterface? = null
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun resolveAppRoutingPlan(
                        settings: AppSettings,
                        packageRoutingRules: Collection<PackageRoutingRule>,
                        installedPackages: Set<String>,
                    ) = expectedPlan

                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                        appRoutingPlan: VpnAppRoutingPlan,
                        interfaceSettings: AppSettings,
                        httpProxyPort: Int?,
                        networkParameters: VpnTunnelNetworkParameters,
                        profileInterface: VpnProfileInterface?,
                    ): VpnTunnelBuilder =
                        object : VpnTunnelBuilder {
                            override fun establish(): VpnTunnelSession = expected
                        }.also {
                            receivedPlan = appRoutingPlan
                            receivedSettings = interfaceSettings
                            receivedProfile = profileInterface
                        }
                }

            val session =
                DefaultVpnTunnelSessionProvider().establish(
                    host,
                    dns = "1.1.1.1",
                    ipv6 = true,
                    appRoutingPlan = expectedPlan,
                    interfaceSettings = expectedSettings,
                    profileInterface = profile,
                )

            assertSame(expected, session)
            assertSame(expectedPlan, receivedPlan)
            assertSame(expectedSettings, receivedSettings)
            assertSame(profile, receivedProfile)
        }

    @Test
    fun throwsWhenBuilderDoesNotProduceSession() =
        runTest {
            val host =
                object : VpnTunnelBuilderHost {
                    override suspend fun resolveAppRoutingPlan(
                        settings: AppSettings,
                        packageRoutingRules: Collection<PackageRoutingRule>,
                        installedPackages: Set<String>,
                    ) = VpnAppRoutingPlan.Disallow(emptySet())

                    override suspend fun createTunnelBuilder(
                        dns: String,
                        ipv6: Boolean,
                        appRoutingPlan: VpnAppRoutingPlan,
                        interfaceSettings: AppSettings,
                        httpProxyPort: Int?,
                        networkParameters: VpnTunnelNetworkParameters,
                        profileInterface: VpnProfileInterface?,
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
                        interfaceSettings = AppSettings.getDefaultInstance(),
                    )
                }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
}

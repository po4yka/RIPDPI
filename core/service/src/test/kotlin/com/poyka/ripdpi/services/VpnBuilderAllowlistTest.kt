package com.poyka.ripdpi.services

import android.content.pm.PackageManager
import android.net.VpnService
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [VpnBuilderAllowlistTest.PackageRemovalBuilder::class])
class VpnBuilderAllowlistTest {
    @Test
    fun `builder refuses empty effective allowlist after package removal`() {
        val controller = Robolectric.buildService(RipDpiVpnService::class.java)
        val service = controller.get()
        initializePausedVpnService(service, PausedServiceReconstructionFixture(Mode.VPN))
        service.vpnDhtMitigationPolicy = DefaultVpnDhtMitigationPolicy(AssetDhtTriggerCidrsCatalogProvider(service))
        controller.create()
        service.underlyingNetworkBinder = VpnUnderlyingNetworkBinder(service, service.directDnsUnderlayAuthority)
        try {
            assertThrows(IllegalStateException::class.java) { build(service, setOf("removed.app")) }
            build(service, setOf("removed.app", "present.app"))
        } finally {
            controller.destroy()
            runBlocking { awaitPausedServiceDestruction(service) }
        }
    }

    private fun build(
        service: RipDpiVpnService,
        packages: Set<String>,
    ) = runBlocking {
        service.createTunnelBuilder(
            dns = "1.1.1.1",
            ipv6 = false,
            appRoutingPlan = VpnAppRoutingPlan.AllowOnly(packages),
            interfaceSettings = AppSettings.getDefaultInstance(),
            httpProxyPort = null,
            networkParameters = VpnTunnelNetworkParameters(),
            profileInterface = null,
        )
    }

    @Implements(VpnService.Builder::class)
    class PackageRemovalBuilder {
        @RealObject private lateinit var builder: VpnService.Builder

        // Android's String overloads use libcore-only InetAddress.parseNumericAddress.
        @Implementation
        fun addAddress(
            address: String,
            prefixLength: Int,
        ): VpnService.Builder = builder.addAddress(java.net.InetAddress.getByName(address), prefixLength)

        @Implementation
        fun addRoute(
            address: String,
            prefixLength: Int,
        ): VpnService.Builder = builder.addRoute(java.net.InetAddress.getByName(address), prefixLength)

        @Implementation
        fun addDnsServer(address: String): VpnService.Builder =
            builder.addDnsServer(java.net.InetAddress.getByName(address))

        @Implementation
        fun addAllowedApplication(pkg: String): VpnService.Builder {
            if (pkg == "removed.app") throw PackageManager.NameNotFoundException()
            return builder
        }
    }
}

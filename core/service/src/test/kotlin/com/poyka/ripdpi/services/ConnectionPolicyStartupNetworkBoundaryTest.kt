package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ConnectionPolicyStartupNetworkBoundaryTest {
    @Test
    fun `initial policy decision surface cannot use network io APIs`() {
        val forbiddenInitialPolicyApis =
            listOf(
                "android.net.DnsResolver",
                "DatagramSocket",
                "InetAddress.getByName(",
                "InetAddress.getAllByName(",
            )

        initialPolicySources.forEach { relativePath ->
            val source = sourceFile(relativePath).readText()
            forbiddenInitialPolicyApis.forEach { forbiddenApi ->
                assertFalse(
                    "$relativePath must remain a pure pre-runtime decision surface; found $forbiddenApi",
                    source.contains(forbiddenApi),
                )
            }
        }
    }

    @Test
    fun `new service network io requires explicit post-runtime allowlisting`() {
        val forbiddenStartupProbeMarkers =
            listOf(
                "www.google.com",
                "CANARY_DOMAIN",
                "REFERENCE_DNS_SERVER",
                "REFERENCE_DNS_PORT",
            )
        val guardedNetworkApis =
            listOf(
                "DatagramSocket",
                "InetAddress.getByName(",
                "InetAddress.getAllByName(",
            )

        serviceSourceDirectory()
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .forEach { file ->
                val source = file.readText()
                forbiddenStartupProbeMarkers.forEach { marker ->
                    assertFalse(
                        "${file.name} reintroduces forbidden startup DNS marker $marker",
                        source.contains(marker),
                    )
                }
                val usesGuardedNetworkApi = guardedNetworkApis.any(source::contains)
                assertFalse(
                    "${file.name} adds direct network I/O without explicit post-runtime review",
                    usesGuardedNetworkApi && file.name !in postRuntimeNetworkIoAllowlist,
                )
            }
    }

    private fun sourceFile(relativePath: String): File = File(serviceSourceDirectory(), relativePath)

    private fun serviceSourceDirectory(): File =
        listOf(
            File("src/main/kotlin/com/poyka/ripdpi/services"),
            File("core/service/src/main/kotlin/com/poyka/ripdpi/services"),
        ).first { directory -> directory.isDirectory }

    private companion object {
        val initialPolicySources =
            listOf(
                "ConnectionPolicyResolver.kt",
                "ConnectionPolicyDnsSelector.kt",
                "ConnectionPolicyRuntimeContextAssembler.kt",
                "ConnectionPolicySignatureBuilder.kt",
                "NetworkFingerprintProvider.kt",
                "RememberedConnectionPolicyMatcher.kt",
                "ResolverMappingPolicy.kt",
            )

        val postRuntimeNetworkIoAllowlist =
            setOf(
                "CaptivePortalDnsAssist.kt",
                "CloudflarePublishRuntime.kt",
                "FlowAppAttributionStore.kt",
                "RipDpiVpnService.kt",
                "VpnUnderlyingNetworkBinder.kt",
                "WarpEndpointProbe.kt",
                "WarpEndpointScannerSupport.kt",
            )
    }
}

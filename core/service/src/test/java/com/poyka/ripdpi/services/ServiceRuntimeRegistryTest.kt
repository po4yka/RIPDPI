package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRuntimeRegistryTest {
    @Test
    fun `register keeps vpn and proxy runtimes independently`() =
        runTest {
            val registry = DefaultServiceRuntimeRegistry()
            val vpnSession = VpnRuntimeSession(runtimeId = "vpn-runtime")
            val proxySession = ProxyRuntimeSession(runtimeId = "proxy-runtime")

            registry.register(vpnSession)
            registry.register(proxySession)

            assertSame(vpnSession, registry.current(Mode.VPN))
            assertSame(proxySession, registry.current(Mode.Proxy))
            assertEquals(
                setOf(Mode.VPN, Mode.Proxy),
                registry.runtimes.value.keys,
            )
        }

    @Test
    fun `stale unregister does not clear newer runtime for same mode`() =
        runTest {
            val registry = DefaultServiceRuntimeRegistry()
            val firstSession = VpnRuntimeSession(runtimeId = "vpn-runtime-1")
            val replacementSession = VpnRuntimeSession(runtimeId = "vpn-runtime-2")

            registry.register(firstSession)
            registry.register(replacementSession)
            registry.unregister(mode = Mode.VPN, runtimeId = firstSession.runtimeId)

            assertSame(replacementSession, registry.current(Mode.VPN))
        }

    @Test
    fun `active connection projection reflects per mode runtime policies`() =
        runTest {
            val registry = DefaultServiceRuntimeRegistry()
            val store =
                DefaultActiveConnectionPolicyStore.createForTests(
                    serviceRuntimeRegistry = registry,
                    scope = backgroundScope,
                )
            val vpnSession = VpnRuntimeSession(runtimeId = "vpn-runtime")
            val proxySession = ProxyRuntimeSession(runtimeId = "proxy-runtime")

            registry.register(vpnSession)
            registry.register(proxySession)
            runCurrent()

            vpnSession.updateActiveConnectionPolicy(testActivePolicy(mode = Mode.VPN, fingerprintHash = "vpn-fp"))
            proxySession.updateActiveConnectionPolicy(testActivePolicy(mode = Mode.Proxy, fingerprintHash = "proxy-fp"))
            runCurrent()

            assertEquals("vpn-fp", store.current(Mode.VPN)?.policy?.fingerprintHash)
            assertEquals("proxy-fp", store.current(Mode.Proxy)?.policy?.fingerprintHash)

            proxySession.clearActiveConnectionPolicy()
            runCurrent()

            assertNull(store.current(Mode.Proxy))
            assertEquals("vpn-fp", store.current(Mode.VPN)?.policy?.fingerprintHash)
        }

    private fun testActivePolicy(
        mode: Mode,
        fingerprintHash: String,
    ): ActiveConnectionPolicy =
        ActiveConnectionPolicy(
            mode = mode,
            policy =
                RememberedNetworkPolicyJson(
                    fingerprintHash = fingerprintHash,
                    mode = mode.preferenceValue,
                    summary =
                        NetworkFingerprintSummary(
                            transport = "wifi",
                            networkState = "validated",
                            identityKind = "wifi",
                            privateDnsMode = "system",
                            dnsServerCount = 2,
                        ),
                    proxyConfigJson = "{}",
                ),
            policySignature = "${mode.preferenceValue}::$fingerprintHash",
            appliedAt = 123L,
        )
}

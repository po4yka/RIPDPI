package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun `active connection projection retains runtime registry without external subscribers`() =
        runTest {
            val registry = CountingServiceRuntimeRegistry()
            val store =
                DefaultActiveConnectionPolicyStore.createForTests(
                    serviceRuntimeRegistry = registry,
                    scope = backgroundScope,
                )
            val vpnSession = CountingRuntimeHandle(mode = Mode.VPN, runtimeId = "vpn-runtime")

            runCurrent()

            assertEquals(1, registry.subscriptionCount)

            registry.register(vpnSession)
            runCurrent()

            assertEquals(1, vpnSession.policySubscriptionCount)

            vpnSession.updateActiveConnectionPolicy(testActivePolicy(mode = Mode.VPN, fingerprintHash = "vpn-fp"))
            runCurrent()

            assertEquals("vpn-fp", store.current(Mode.VPN)?.policy?.fingerprintHash)
        }

    @Test
    fun `active connection projection releases runtime registry when owning scope is cancelled`() =
        runTest {
            val registry = CountingServiceRuntimeRegistry()
            val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            DefaultActiveConnectionPolicyStore.createForTests(
                serviceRuntimeRegistry = registry,
                scope = sourceScope,
            )
            runCurrent()

            assertEquals(1, registry.subscriptionCount)

            sourceScope.cancel()
            runCurrent()

            assertEquals(0, registry.subscriptionCount)
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

private class CountingServiceRuntimeRegistry : ServiceRuntimeRegistry {
    private val state = MutableStateFlow<Map<Mode, ServiceRuntimeHandle>>(emptyMap())
    private val countedState = CountingStateFlow(state)
    val subscriptionCount: Int
        get() = countedState.collectorCount

    override val runtimes: StateFlow<Map<Mode, ServiceRuntimeHandle>> = countedState

    override fun register(handle: ServiceRuntimeHandle) {
        state.value = state.value + (handle.mode to handle)
    }

    override fun unregister(
        mode: Mode,
        runtimeId: String,
    ) {
        if (state.value[mode]?.runtimeId == runtimeId) {
            state.value = state.value - mode
        }
    }
}

private class CountingRuntimeHandle(
    override val mode: Mode,
    override val runtimeId: String,
) : ServiceRuntimeHandle {
    private val activeConnectionPolicyState = MutableStateFlow<ActiveConnectionPolicy?>(null)
    private val countedActiveConnectionPolicy = CountingStateFlow(activeConnectionPolicyState)
    val policySubscriptionCount: Int
        get() = countedActiveConnectionPolicy.collectorCount

    override val activeConnectionPolicy: StateFlow<ActiveConnectionPolicy?> = countedActiveConnectionPolicy

    fun updateActiveConnectionPolicy(policy: ActiveConnectionPolicy) {
        activeConnectionPolicyState.value = policy
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class CountingStateFlow<T>(
    private val delegate: StateFlow<T>,
) : StateFlow<T> {
    var collectorCount: Int = 0
        private set

    override val replayCache: List<T>
        get() = delegate.replayCache

    override val value: T
        get() = delegate.value

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        collectorCount += 1
        try {
            delegate.collect(collector)
        } finally {
            collectorCount -= 1
        }
    }
}

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeExitCleanupTest {
    @Test
    fun vpnProxyExitStopsRelayAndWarp() = verifyExitCleanup(vpn = true, exited = Component.Proxy)

    @Test
    fun vpnCleanProxyExitStopsRelayAndWarp() = verifyExitCleanup(vpn = true, exited = Component.Proxy, exitCode = 0)

    @Test
    fun vpnRelayExitStopsProxyAndWarp() = verifyExitCleanup(vpn = true, exited = Component.Relay)

    @Test
    fun vpnWarpExitStopsProxyAndRelay() = verifyExitCleanup(vpn = true, exited = Component.Warp)

    @Test
    fun vpnAwgExitStopsProxy() = verifyExitCleanup(vpn = true, exited = Component.Awg)

    @Test
    fun vpnProxyExitStopsAwg() = verifyExitCleanup(vpn = true, exited = Component.Proxy, useAwg = true)

    @Test
    fun proxyExitStopsRelayAndWarp() = verifyExitCleanup(vpn = false, exited = Component.Proxy)

    @Test
    fun proxyWarpExitStopsProxyAndRelay() = verifyExitCleanup(vpn = false, exited = Component.Warp)

    @Test
    fun proxyAwgExitStopsProxy() = verifyExitCleanup(vpn = false, exited = Component.Awg)

    @Test
    fun proxyExitStopsAwg() = verifyExitCleanup(vpn = false, exited = Component.Proxy, useAwg = true)

    @Test
    fun legacySkipShutdownStillStopsLiveWorkers() =
        runTest {
            val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), vpn = true)
            fixture.start()

            fixture.stack.stop(skipRuntimeShutdown = true)
            runCurrent()

            assertEquals(1, fixture.proxyFactory.lastRuntime.stopCount)
            assertEquals(1, fixture.relayFactory.lastRuntime.stopCount)
            assertEquals(1, fixture.warpFactory.lastRuntime.stopCount)
            assertFalse(fixture.stack.hasOwnedRuntime)
            assertEquals(0, fixture.stopRequests)
        }

    @Test
    fun relayCrashRetainsUnstoppedProxyUntilCleanupRetry() =
        runTest {
            val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), vpn = true)
            fixture.start()
            val survivingProxy = fixture.proxyFactory.lastRuntime
            survivingProxy.keepRunningOnStop = true

            fixture.relayFactory.lastRuntime.complete(17)
            runCurrent()
            testScheduler.advanceTimeBy(101)
            runCurrent()

            assertTrue(fixture.stopFailure is RuntimeCleanupPendingException)
            assertTrue(fixture.stack.hasOwnedRuntime)
            assertEquals(1, survivingProxy.stopCount)
            assertEquals(1, fixture.warpFactory.lastRuntime.stopCount)

            survivingProxy.keepRunningOnStop = false
            fixture.stack.stop(skipRuntimeShutdown = true)
            runCurrent()

            assertEquals(2, survivingProxy.stopCount)
            assertFalse(fixture.stack.hasOwnedRuntime)
            fixture.start()
            assertTrue(fixture.stack.hasOwnedRuntime)
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    private fun verifyExitCleanup(
        vpn: Boolean,
        exited: Component,
        exitCode: Int = 17,
        useAwg: Boolean = exited == Component.Awg,
    ) = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), vpn)
        fixture.start(useAwg)

        when (exited) {
            Component.Proxy -> fixture.proxyFactory.lastRuntime.complete(exitCode)
            Component.Relay -> fixture.relayFactory.lastRuntime.complete(exitCode)
            Component.Warp -> fixture.warpFactory.lastRuntime.complete(exitCode)
            Component.Awg -> fixture.awgFactory.lastRuntime.complete(exitCode)
        }
        runCurrent()

        assertEquals(1, fixture.stopRequests)
        assertEquals(null, fixture.stopFailure)
        assertEquals(if (exited == Component.Proxy) 0 else 1, fixture.proxyFactory.lastRuntime.stopCount)
        if (useAwg) {
            assertEquals(if (exited == Component.Awg) 0 else 1, fixture.awgFactory.lastRuntime.stopCount)
        } else {
            assertEquals(if (exited == Component.Relay) 0 else 1, fixture.relayFactory.lastRuntime.stopCount)
            assertEquals(if (exited == Component.Warp) 0 else 1, fixture.warpFactory.lastRuntime.stopCount)
        }
        assertFalse(fixture.stack.hasOwnedRuntime)

        fixture.start(useAwg)
        assertTrue(fixture.stack.hasOwnedRuntime)
        assertEquals(2, fixture.proxyFactory.runtimes.size)
        fixture.stack.stop(skipRuntimeShutdown = false)
        runCurrent()
        assertFalse(fixture.stack.hasOwnedRuntime)
        assertEquals("Expected stop callbacks must not start another teardown", 1, fixture.stopRequests)
    }

    private enum class Component { Proxy, Relay, Warp, Awg }

    private class Fixture(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        private val vpn: Boolean,
    ) {
        val proxyFactory = TestRipDpiProxyFactory()
        val relayFactory = TestRipDpiRelayFactory()
        val warpFactory = TestRipDpiWarpFactory()
        val awgFactory = TestRipDpiAmneziaWgFactory()
        var stopRequests = 0
        var stopFailure: Throwable? = null

        private val proxy =
            ProxyRuntimeSupervisor(scope, dispatcher, proxyFactory, TestNativeNetworkSnapshotProvider(), 100L)
        private val warp = WarpRuntimeSupervisor(scope, dispatcher, warpFactory, TestWarpRuntimeConfigResolver())
        private val awg =
            AmneziaWgRuntimeSupervisor(scope, dispatcher, awgFactory, RecordingAmneziaWgRuntimeConfigResolver())
        private val relay =
            UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = relayFactory,
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                runtimeConfigResolver =
                    object : UpstreamRelayRuntimeConfigResolver {
                        override suspend fun resolve(
                            config: RipDpiRelayConfig,
                            inputs: RelayResolutionInputs,
                            expectedSelection: RuntimeConfigurationSelection,
                        ) = sampleResolvedRelayConfig(config.kind, config.profileId)
                    },
            )
        val stack = SharedProxyRuntimeStack(relay, warp, awg, proxy)
        private val vpnHandler =
            VpnSupervisorExitHandler(
                host = TestVpnServiceHost(scope),
                ioDispatcher = dispatcher,
                proxyRuntimeStack = stack,
                upstreamRelaySupervisor = relay,
                warpRuntimeSupervisor = warp,
                amneziaWgRuntimeSupervisor = awg,
                updateStatus = { _, _ -> },
                markForeignRelayFailed = {},
                stopService = ::stop,
            )
        private val proxyHandler =
            ProxySupervisorExitHandler(
                host = TestProxyServiceHost(scope),
                ioDispatcher = dispatcher,
                upstreamRelaySupervisor = relay,
                warpRuntimeSupervisor = warp,
                amneziaWgRuntimeSupervisor = awg,
                proxyRuntimeSupervisor = proxy,
                updateStatus = { _, _ -> },
                markForeignRelayFailed = {},
                stopService = ::stop,
            )

        private suspend fun stop(skipRuntimeShutdown: Boolean) {
            stopRequests += 1
            stopFailure = runCatching { stack.stop(skipRuntimeShutdown) }.exceptionOrNull()
        }

        suspend fun start(useAwg: Boolean = false) {
            if (useAwg) {
                awg.start(
                    AwgActivationRequest(
                        profileId = "awg-profile",
                        privateKey = "private-test-key",
                        peerPublicKey = "peer-test-key",
                        endpointHost = "vpn.example.org",
                        endpointPort = 51820,
                        interfaceAddressV4 = "10.8.0.2/32",
                    ),
                    if (vpn) vpnHandler::handleAwgExit else proxyHandler::handleAwgExit,
                )
            } else {
                relay.start(
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                    config = RipDpiRelayConfig(enabled = true, kind = RelayKindVlessReality, profileId = "relay"),
                    inputs = testRelayResolutionInputs(),
                    expectedSelection = RuntimeConfigurationSelection("native"),
                    onUnexpectedExit = if (vpn) vpnHandler::handleRelayExit else proxyHandler::handleRelayExit,
                )
                warp.start(
                    RipDpiWarpConfig(enabled = true),
                    RequestedWarpRuntimeReference("warp-test", 0L),
                    if (vpn) vpnHandler::handleWarpExit else proxyHandler::handleWarpExit,
                )
            }
            proxy.start(
                RipDpiProxyUIPreferences(),
                if (vpn) vpnHandler::handleProxyExit else proxyHandler::handleProxyExit,
            )
        }
    }
}

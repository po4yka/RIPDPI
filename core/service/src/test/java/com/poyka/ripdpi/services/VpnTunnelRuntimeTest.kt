package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelRuntimeTest {
    @Test
    fun successfulStartStoresDnsSignatureAndSyncsHost() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
            )

            assertTrue(runtime.isRunning)
            assertEquals(
                dnsSignature(AppSettingsSerializer.defaultValue.activeDnsSettings(), null),
                runtime.currentDnsSignature,
            )
            assertEquals(0L, runtime.tunnelRecoveryRetryCount)
            assertEquals(1, host.underlyingNetworkSyncs)
        }

    @Test
    fun secondStartIncrementsRecoveryRetryCount() =
        runTest {
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = TestVpnTunnelSession(events = events),
                        ),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null)
            runtime.stop()
            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null)

            assertEquals(1L, runtime.tunnelRecoveryRetryCount)
        }

    @Test
    fun tunnelStartFailureClosesEstablishedSession() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events).apply { startFailure = IllegalStateException("boom") }
            val session = TestVpnTunnelSession(events = events)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = session,
                        ),
                )

            runCatching {
                runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null)
            }

            assertTrue(session.closed)
            assertFalse(runtime.isRunning)
        }

    @Test
    fun stopClosesBridgeAndSessionEvenWhenBridgeStopFails() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = TestTun2SocksBridge(events).apply { stopFailure = IllegalStateException("stop boom") }
            val session = TestVpnTunnelSession(events = events)
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider =
                        TestVpnTunnelSessionProvider(
                            events = events,
                            session = session,
                        ),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null)
            val failure = runCatching { runtime.stop() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, bridge.stopCount)
            assertTrue(session.closed)
            assertFalse(runtime.isRunning)
        }
}

package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
                logContext = null,
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

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            runtime.stop()
            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)

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
                runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
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

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            val failure = runCatching { runtime.stop() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, bridge.stopCount)
            assertTrue(session.closed)
            assertFalse(runtime.isRunning)
        }

    @Test
    fun startKeepsUdpRelayWhenQuicBypassStrategyIsDisabled() =
        runTest {
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)

            assertEquals("udp", bridge.startedConfig?.socks5Udp)
        }

    @Test
    fun startKeepsUdpRelayWhenQuicBypassStrategyIsEnabled() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDesyncUdp(true)
                    .build()
            val bridge = TestTun2SocksBridge()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(settings.activeDnsSettings(), overrideReason = null, logContext = null)

            assertEquals("udp", bridge.startedConfig?.socks5Udp)
        }

    // -- Error path tests -----------------------------------------------------

    @Test
    fun `start when already running throws`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            val error =
                runCatching {
                    runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
        }

    @Test
    fun `pollTelemetry returns failure Result when bridge throws`() =
        runTest {
            val bridge = TestTun2SocksBridge().apply { telemetryFailure = IOException("telemetry crash") }
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            val result = runtime.pollTelemetry()

            assertTrue(result.isFailure)
        }

    @Test
    fun `stop is no-op when no tunnel session exists`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.stop()

            assertFalse(runtime.isRunning)
        }

    @Test
    fun `resetRuntimeState clears signature and counters`() =
        runTest {
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )

            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            runtime.stop()
            runtime.start(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null, logContext = null)
            assertEquals(1L, runtime.tunnelRecoveryRetryCount)

            runtime.stop()
            runtime.resetRuntimeState()

            assertNull(runtime.currentDnsSignature)
            assertEquals(0L, runtime.tunnelRecoveryRetryCount)
        }
}
